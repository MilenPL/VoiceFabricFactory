package com.voiceforge.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voiceforge.app.audio.AppAudioPlayer
import com.voiceforge.app.data.AppConfig
import com.voiceforge.app.data.GenSettings
import com.voiceforge.app.data.QueueItem
import com.voiceforge.app.data.SavedItem
import com.voiceforge.app.data.Storage
import com.voiceforge.app.data.VoiceSample
import com.voiceforge.app.engine.AudioProcessor
import com.voiceforge.app.engine.SpeakerProfile
import com.voiceforge.app.engine.TtsEngine
import com.voiceforge.app.engine.xtts.XttsOnnxEngine
import com.voiceforge.app.ui.Strings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** One finished (or in-flight) generation — feeds the Compose result player. */
data class GenResult(
    val file: File,
    val text: String,
    val settings: GenSettings,
)

/**
 * Everything the screens render. [status] holds a [Strings] key (or a raw
 * engine progress message — Strings.t passes unknown keys through unchanged),
 * so switching the UI language re-renders it instantly.
 *
 * The delivery controls live here too, because the batch queue generates with
 * "the current UI voice/mood/settings" selected on the Compose tab.
 */
data class UiState(
    val status: String = "",
    val isGenerating: Boolean = false,
    val lastResult: GenResult? = null,
    val batchIndex: Int = 0,
    val batchTotal: Int = 0,
    // ---- current UI controls ----
    val selectedVoiceId: String = "",
    val language: String = "pl",
    val mood: String = "Neutral",
    val pitch: Float = 0f,
    val speed: Float = 1f,
    val gain: Float = 0f,
    val normalize: Boolean = false,
    // ---- change counters (screens re-read Storage when these tick) ----
    val voicesVersion: Int = 0,
    val libraryVersion: Int = 0,
) {
    val lastResultFile: File? get() = lastResult?.file
    val isBatchRunning: Boolean get() = batchTotal > 0
}

/** Per-item batch callbacks — all invoked on the main thread. */
interface BatchListener {
    fun onStart(itemId: String) {}
    fun onDone(itemId: String, saved: SavedItem) {}
    fun onError(itemId: String, error: String) {}
    fun onFinished() {}
}

/**
 * Owns the TTS engine, the shared audio player and all generation state.
 * One instance per Activity, shared by the four tabs.
 */
class EngineViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** Flip to true to run the UI against the tone-generator stub instead. */
        const val USE_FALLBACK_ENGINE = false
    }

    // Real XTTS-v2 ONNX engine (downloads/pushes model files on first prepare).
    private val engine: TtsEngine =
        if (USE_FALLBACK_ENGINE) com.voiceforge.app.engine.FallbackTtsEngine() else XttsOnnxEngine()

    val player = AppAudioPlayer(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Speaker conditioning cache, keyed by voice id — skips re-analysis. */
    private val profileCache = mutableMapOf<String, SpeakerProfile>()

    @Volatile
    private var prepared = false

    /** Serialises prepare attempts (boot + first Generate can overlap). */
    private val prepareMutex = Mutex()

    // ------------------------------------------------------------- lifecycle

    /** One-shot config load: UI language + normalize flag. */
    fun initFromConfig() {
        val cfg = runCatching { Storage.loadConfig() }.getOrDefault(AppConfig())
        Strings.lang = cfg.lang
        _state.update { it.copy(normalize = cfg.normalize) }
    }

    /** Called from a LaunchedEffect at app start → engine.prepare. */
    fun prepare() {
        if (prepared) return
        viewModelScope.launch {
            setStatus("model_loading")
            try {
                runPrepare()
                // Staging only holds transient results; nothing is kept between runs.
                withContext(Dispatchers.IO) { Storage.stagingDir.listFiles()?.forEach { it.delete() } }
                setStatus("")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Surface the reason (missing model / download error …) in the UI;
                // `prepared` stays false so the next Generate retries prepare().
                setStatus(e.message?.takeIf { it.isNotBlank() } ?: "failed")
            }
        }
    }

    /**
     * Runs [TtsEngine.prepare] exactly once (concurrent callers wait), with
     * download progress streamed into [UiState.status]. Rethrows on failure so
     * generate() shows the usual error snackbar.
     */
    private suspend fun runPrepare() {
        prepareMutex.withLock {
            if (prepared && engine.isReady) return
            withContext(Dispatchers.IO) { engine.prepare { msg -> setStatus(msg) } }
            prepared = true
        }
    }

    /**
     * Re-runs the prepare flow if it has not succeeded yet — called at the
     * start of every generation so a failed first prepare is retryable from
     * the UI. Rethrows on failure so generate() shows the error snackbar.
     */
    private suspend fun ensurePrepared() {
        if (prepared && engine.isReady) return
        setStatus("model_loading")
        try {
            runPrepare()
            setStatus("")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setStatus(e.message?.takeIf { it.isNotBlank() } ?: "failed")
            throw e
        }
    }

    override fun onCleared() {
        player.release()
        runCatching { engine.close() }
        super.onCleared()
    }

    // ------------------------------------------------------------ ui setters

    fun setStatus(key: String) = _state.update { it.copy(status = key) }

    fun setVoice(id: String) = _state.update { it.copy(selectedVoiceId = id) }

    fun setLanguage(code: String) = _state.update { it.copy(language = code) }

    fun setMood(mood: String) = _state.update { it.copy(mood = mood) }

    fun setDelivery(pitch: Float, speed: Float, gain: Float) =
        _state.update { it.copy(pitch = pitch, speed = speed, gain = gain) }

    fun setPitch(v: Float) = _state.update { it.copy(pitch = v) }

    fun setSpeed(v: Float) = _state.update { it.copy(speed = v) }

    fun setGain(v: Float) = _state.update { it.copy(gain = v) }

    fun setNormalize(v: Boolean) {
        _state.update { it.copy(normalize = v) }
        persistConfig()
    }

    fun setUiLanguage(lang: String) {
        Strings.lang = lang
        persistConfig()
    }

    private fun persistConfig() {
        val s = _state.value
        runCatching { Storage.saveConfig(AppConfig(lang = Strings.lang, normalize = s.normalize)) }
    }

    fun notifyVoicesChanged() = _state.update { it.copy(voicesVersion = it.voicesVersion + 1) }

    fun notifyLibraryChanged() = _state.update { it.copy(libraryVersion = it.libraryVersion + 1) }

    // ----------------------------------------------------------- generation

    /**
     * prepare → cached/fresh speaker profile → synthesize → effects/mp3.
     * Runs off the main thread; status keys are updated along the way.
     */
    private suspend fun synth(text: String, settings: GenSettings): File {
        ensurePrepared()
        val voices = Storage.listVoices()
        val voice = voices.find { it.id == settings.voiceId }
            ?: voices.find { it.name == settings.voiceName }
            ?: throw IllegalStateException(Strings.t("need_voice"))

        val params = settings.toParams()
        setStatus("analyzing_voice")
        val profile = profileFor(voice)

        val outWav = File(Storage.stagingDir, "gen_${token()}.wav")
        val outMp3 = File(Storage.stagingDir, "result_${token()}.mp3")
        setStatus("synthesizing")
        engine.synthesize(profile, text, params, outWav) { msg -> setStatus(msg) }

        setStatus("applying_effects")
        withContext(Dispatchers.IO) {
            AudioProcessor.process(outWav, outMp3, params)   // effects + MP3 encode
        }
        outWav.delete()
        return outMp3
    }

    private suspend fun profileFor(voice: VoiceSample): SpeakerProfile {
        profileCache[voice.id]?.let { return it }
        val file = Storage.voiceFile(voice)
        if (!file.exists()) throw IllegalStateException(Strings.t("need_voice"))
        val profile = engine.extractProfile(file) { msg -> setStatus(msg) }
        if (profile.isValid) profileCache[voice.id] = profile
        return profile
    }

    /** Single generation; the result lands in [UiState.lastResult]. */
    fun generate(
        text: String,
        settings: GenSettings,
        onDone: (Result<GenResult>) -> Unit = {},
    ) {
        if (_state.value.isGenerating) return
        viewModelScope.launch {
            _state.update { it.copy(isGenerating = true, status = "queued") }
            val result = runCatching {
                val file = withContext(Dispatchers.Default) { synth(text, settings) }
                GenResult(file, text, settings)
            }
            when {
                result.isSuccess -> {
                    val r = result.getOrNull()!!
                    _state.update { it.copy(isGenerating = false, status = "done", lastResult = r) }
                    onDone(Result.success(r))
                }
                else -> {
                    _state.update { it.copy(isGenerating = false, status = "failed") }
                    onDone(Result.failure(result.exceptionOrNull() ?: IllegalStateException()))
                }
            }
        }
    }

    /**
     * Regenerates a Saved item with its stored settings; the fresh file is
     * placed in the Compose player (Save stays enabled for it).
     */
    fun regenerate(item: SavedItem, onDone: (Result<GenResult>) -> Unit) {
        val settings = item.settings
        if (settings == null) {
            onDone(Result.failure(IllegalStateException(Strings.t("no_settings"))))
            return
        }
        val known = Storage.listVoices().any { it.id == settings.voiceId || it.name == settings.voiceName }
        if (!known) {
            onDone(Result.failure(IllegalStateException(Strings.t("voice_missing"))))
            return
        }
        generate(item.text, settings, onDone)
    }

    /** Sequential batch run; every finished item is saved to the library. */
    fun runBatch(
        items: List<QueueItem>,
        settings: GenSettings,
        listener: BatchListener = object : BatchListener {},
    ) {
        if (items.isEmpty() || _state.value.isGenerating) return
        viewModelScope.launch {
            _state.update {
                it.copy(isGenerating = true, status = "queued", batchIndex = 0, batchTotal = items.size)
            }
            for ((n, item) in items.withIndex()) {
                _state.update { s -> s.copy(batchIndex = n + 1) }
                listener.onStart(item.id)
                try {
                    val saved = withContext(Dispatchers.Default) { synthesizeAndSave(item.text, settings) }
                    listener.onDone(item.id, saved)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    listener.onError(item.id, (e.message ?: e.toString()).take(80))
                }
            }
            _state.update {
                it.copy(
                    isGenerating = false,
                    batchIndex = 0,
                    batchTotal = 0,
                    status = "batch_finished",
                    libraryVersion = it.libraryVersion + 1,
                )
            }
            listener.onFinished()
        }
    }

    private suspend fun synthesizeAndSave(text: String, settings: GenSettings): SavedItem {
        val file = synth(text, settings)
        val saved = Storage.addSaved(
            src = file,
            name = suggestName(text),
            text = text,
            mood = settings.mood,
            voiceName = settings.voiceName,
            settings = settings,
        )
        file.delete()
        return saved
    }

    /** Stores the current result in the Saved library (with its settings snapshot). */
    suspend fun saveResult(result: GenResult): Result<SavedItem> {
        val saved = runCatching {
            withContext(Dispatchers.IO) {
                Storage.addSaved(
                    src = result.file,
                    name = suggestName(result.text),
                    text = result.text,
                    mood = result.settings.mood,
                    voiceName = result.settings.voiceName,
                    settings = result.settings,
                )
            }
        }
        if (saved.isSuccess) notifyLibraryChanged()
        return saved
    }

    /** Desktop naming rule: first six words of the text, max 40 chars. */
    fun suggestName(text: String): String {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(6)
            .joinToString(" ")
        val s = if (words.length > 40) words.take(40).trimEnd() + "…" else words
        return s.ifBlank { "Generated speech" }
    }
}

private fun token(): String = UUID.randomUUID().toString().replace("-", "").take(8)
