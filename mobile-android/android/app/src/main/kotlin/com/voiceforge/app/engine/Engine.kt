package com.voiceforge.app.engine

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Delivery settings produced by the mood / loudness / speed / pitch panel. */
data class SynthParams(
    val language: String = "pl",
    val mood: String = "Neutral",
    val pitchSemitones: Float = 0f,
    val speed: Float = 1f,
    val gainDb: Float = 0f,
    val normalize: Boolean = false,
)

/**
 * Opaque speaker conditioning extracted from a 2–5 minute voice sample
 * (GPT conditioning latents + ResNet speaker embedding of XTTS-v2).
 */
class SpeakerProfile internal constructor(
    internal val condLatent: FloatArray,   // flattened [1024][T]
    internal val condT: Int,
    internal val speakerEmb: FloatArray,   // flattened [512][1]
) {
    val isValid: Boolean get() = condLatent.isNotEmpty() && speakerEmb.isNotEmpty()
}

class EngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Text-to-speech engine contract.
 *
 * Implementations:
 *  - [XttsOnnxEngine] (engine/xtts/) — real XTTS-v2 ONNX pipeline, fully on-device.
 *  - [FallbackTtsEngine] — development stub so the UI is testable without the model.
 */
interface TtsEngine {
    val isReady: Boolean

    /** Called once at startup. May download the model on first run. */
    suspend fun prepare(onProgress: (String) -> Unit)

    /** Extract the speaker conditioning from a sample file (wav/mp3 — impl decodes as needed). */
    suspend fun extractProfile(
        sampleFile: File,
        onProgress: (String) -> Unit = {},
    ): SpeakerProfile

    /** Generate raw speech (WAV) — effects/mp3 encoding are applied afterwards by [AudioProcessor]. */
    suspend fun synthesize(
        profile: SpeakerProfile,
        text: String,
        params: SynthParams,
        outWav: File,
        onProgress: (String) -> Unit = {},
    )

    fun close() {}
}

/**
 * Post-processing (mood/loudness/pitch + loudness normalization + MP3 encode)
 * lives in `engine/Audio.kt` ([AudioProcessor]) — same package, same contract:
 *   pitch (asetrate) -> [loudnorm I=-16] -> volume(gain) -> 44100 Hz MP3.
 * `speed` is applied by the engine on the XTTS latents (native, pitch-stable).
 */

/**
 * Development stand-in: no model needed, produces a soft tone whose length
 * follows the text so the whole UI flow (play/save/download/batch) is testable.
 * Produces a file containing WAV bytes; ExoPlayer/MediaExtractor sniff content,
 * so naming it .mp3 still plays.
 */
class FallbackTtsEngine : TtsEngine {
    override var isReady: Boolean = true
        private set

    override suspend fun prepare(onProgress: (String) -> Unit) {
        onProgress("Development engine ready")
    }

    override suspend fun extractProfile(sampleFile: File, onProgress: (String) -> Unit): SpeakerProfile {
        onProgress("Analyzing voice sample…")
        return SpeakerProfile(FloatArray(1024 * 32), 32, FloatArray(512))
    }

    override suspend fun synthesize(
        profile: SpeakerProfile,
        text: String,
        params: SynthParams,
        outWav: File,
        onProgress: (String) -> Unit,
    ) {
        onProgress("Synthesizing…")
        val sr = 24000
        val seconds = (text.length / 14.0f).coerceIn(1.5f, 30f)
        val n = (sr * seconds).toInt()
        val pcm = ShortArray(n)
        val baseFreq = 160f + (text.hashCode().mod(120)).let { kotlin.math.abs(it) }
        for (i in 0 until n) {
            val t = i.toFloat() / sr
            val env = 0.4f + 0.6f * kotlin.math.abs(sin(2 * PI * 2.1 * t).toFloat())
            val v = sin(2 * PI * baseFreq * t) * 0.25f * env
            pcm[i] = (v * Short.MAX_VALUE).toInt().toShort()
        }
        writeWav(outWav, pcm, sr)
    }

    private fun writeWav(out: File, pcm: ShortArray, sr: Int) {
        val dataLen = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(36 + dataLen)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)
        buf.putShort(1)            // PCM
        buf.putShort(1)            // mono
        buf.putInt(sr)
        buf.putInt(sr * 2)
        buf.putShort(2)
        buf.putShort(16)
        buf.put("data".toByteArray())
        buf.putInt(dataLen)
        for (s in pcm) buf.putShort(s)
        out.parentFile?.mkdirs()
        RandomAccessFile(out, "rw").use { it.write(buf.array()) }
    }
}
