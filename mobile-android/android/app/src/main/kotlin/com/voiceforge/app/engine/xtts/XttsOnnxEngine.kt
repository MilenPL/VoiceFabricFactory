package com.voiceforge.app.engine.xtts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import com.voiceforge.app.audio.PcmDecoder
import com.voiceforge.app.engine.EngineException
import com.voiceforge.app.engine.SpeakerProfile
import com.voiceforge.app.engine.SynthParams
import com.voiceforge.app.engine.TtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min

/**
 * Real on-device XTTS-v2 engine — a 1:1 Kotlin port of
 * `tools/onnx_export/onnx_pipeline.py` (`OnnxPipeline.extract_profile`,
 * `generate_codes`, `synthesize`) on top of ONNX Runtime.
 *
 * Pipeline summary:
 *  - [extractProfile]: decode the sample to mono float @22050 (cond, first 30 s,
 *    6 s chunks, tail < 0.33 s dropped) and @16000 (spk, first 600 s) →
 *    mel80 (log/clamp 1e-5 / mel_stats) → `style_encoder` → mean over chunks →
 *    transpose to (1,32,1024); preemphasis → mel64 → `speaker_encoder` →
 *    (1,512,1). The mel math lives in [Mel], the GPT/vocoder math in
 *    [GptSegmentGenerator] — both are exercised by JVM unit tests against the
 *    Python reference vectors.
 *  - [synthesize]: whitespace-normalize + lowercase (pipeline order), segment
 *    the text so each part is ≤ 401 ids (sentence boundaries preferred), then
 *    per segment [GptSegmentGenerator.generate] → concatenate → temp WAV
 *    (16-bit PCM @24000).
 *
 * Threads: every ONNX run executes on [Dispatchers.IO] (OrtSession.run blocks).
 */
class XttsOnnxEngine : TtsEngine {

    companion object {
        const val SR_OUT = 24000
        const val SR_COND = Mel.SR_COND          // 22050
        const val SR_SPK = Mel.SR_SPK            // 16000
        const val CHUNK_SEC = 6
        const val MAX_REF_SEC = 30
        const val MIN_CHUNK_SEC = 0.33f
        const val MAX_SPK_SEC = 600.0
        const val MAX_TEXT_IDS = 401             // native asserts text fits +2 ≤ 402
    }

    private var env: OrtEnvironment? = null
    private var sessionOptions: OrtSession.SessionOptions? = null
    private val sessions = HashMap<String, OrtSession>()
    private var meta: ModelConfig.Meta? = null
    private var melStats: FloatArray? = null
    private var tokenizer: XttsTokenizer? = null
    private var generator: GptSegmentGenerator? = null

    @Volatile
    private var ready = false

    override val isReady: Boolean get() = ready

    // ================================================================= prepare

    override suspend fun prepare(onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        try {
            val dir = com.voiceforge.app.data.Storage.modelDir
            dir.mkdirs()

            val missing = ModelConfig.URLS.keys.filter { name ->
                val f = File(dir, name)
                !f.exists() || f.length() == 0L
            }
            if (missing.isNotEmpty()) {
                for (name in missing) {
                    val url = ModelConfig.URLS[name]
                        ?: throw EngineException("No URL for $name")
                    download(url, File(dir, name)) { pct, bytes ->
                        onProgress("Downloading $name… $pct% ($bytes bytes)")
                    }
                }
            } else {
                onProgress("Model files present, opening…")
            }

            // metadata
            val metaJson = JSONObject(File(dir, "meta.json").readText())
            meta = ModelConfig.Meta(
                startAudioToken = metaJson.getInt("start_audio_token"),
                stopAudioToken = metaJson.getInt("stop_audio_token"),
                maxTextTokens = metaJson.getInt("max_text_tokens"),
                maxGenMelTokens = metaJson.getInt("max_gen_mel_tokens"),
                codeStrideLen = metaJson.getInt("code_stride_len"),
                outputSampleRate = metaJson.getInt("output_sample_rate"),
                condLen = metaJson.optInt("cond_len", 32),
            )
            val statsArr = JSONArray(File(dir, "mel_stats.json").readText())
            melStats = FloatArray(statsArr.length()) { statsArr.getDouble(it).toFloat() }
            tokenizer = XttsTokenizer(File(dir, "vocab.json"))

            // environment now, graphs lazily on first inference (they are large)
            if (env == null) env = OrtEnvironment.getEnvironment()
            if (sessionOptions == null) {
                val opts = try {
                    OrtSession.SessionOptions().also {
                        it.setInterOpNumThreads(1)
                        it.setIntraOpNumThreads(min(4, Runtime.getRuntime().availableProcessors()))
                    }
                } catch (e: OrtException) {
                    throw EngineException("Could not configure ONNX sessions: ${e.message}", e)
                }
                sessionOptions = opts
            }
            ready = true
            onProgress("Model ready")
        } catch (e: EngineException) {
            throw e
        } catch (e: Exception) {
            throw EngineException("Model preparation failed: ${e.message}", e)
        }
    }

    private fun download(urlString: String, target: File, progress: (Int, Long) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".part")
        try {
            val conn = URL(urlString).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            try {
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    throw EngineException("Download failed for ${target.name}: HTTP ${conn.responseCode}")
                }
                val total = conn.contentLength.toLong()   // may be -1
                var received = 0L
                var lastPct = -1
                conn.getInputStream().use { input ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            received += n
                            if (total > 0) {
                                val pct = ((received * 100) / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    progress(pct, received)
                                }
                            }
                        }
                        out.fd.sync()
                    }
                }
                if (tmp.length() <= 0L) {
                    throw EngineException("Download produced an empty file: ${target.name}")
                }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    throw EngineException("Could not move ${target.name} into place")
                }
                progress(100, target.length())
            } finally {
                conn.disconnect()
            }
        } catch (e: EngineException) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            throw EngineException("Download failed for ${target.name}: ${e.message}", e)
        }
    }

    // =============================================================== sessions

    private fun ensureEnv(): OrtEnvironment = env ?: throw EngineException("Engine not prepared")

    private fun session(name: String): OrtSession {
        synchronized(sessions) {
            sessions[name]?.let { return it }
            val e = ensureEnv()
            val opts = sessionOptions ?: throw EngineException("Engine not prepared")
            val path = File(com.voiceforge.app.data.Storage.modelDir, "$name.onnx")
            if (!path.exists() || path.length() == 0L) {
                throw EngineException("Model file missing: ${path.name}")
            }
            try {
                val s = e.createSession(path.absolutePath, opts)
                sessions[name] = s
                return s
            } catch (ex: OrtException) {
                throw EngineException("Could not load $name.onnx: ${ex.message}", ex)
            }
        }
    }

    private fun ensureReadyState() {
        if (!ready) throw EngineException("Engine not prepared yet")
    }

    private fun ensureGenerator(): GptSegmentGenerator {
        generator?.let { return it }
        val m = meta ?: throw EngineException("meta.json not loaded")
        synchronized(this) {
            generator?.let { return it }
            val g = GptSegmentGenerator(
                env = ensureEnv(),
                meta = m,
                prefixSession = session("gpt_prefix"),
                prefillSession = session("gpt_prefill"),
                decodeSession = session("gpt_decode"),
                latentsSession = session("gpt_latents"),
                vocoderSession = session("hifigan_decoder"),
            )
            generator = g
            return g
        }
    }

    private inline fun <T> runGraph(name: String, feeds: Map<String, OnnxTensor>, block: (OrtSession.Result) -> T): T {
        val s = session(name)
        try {
            s.run(feeds).use { result -> return block(result) }
        } catch (e: OrtException) {
            throw EngineException("$name inference failed: ${e.message}", e)
        }
    }

    /** Named tensor output of a result (Result exposes Optional, not nullable getValue). */
    private fun OrtSession.Result.tensor(name: String): OnnxTensor {
        val v = try {
            get(name).orElseThrow { EngineException("Missing output '$name'") }
        } catch (e: OrtException) {
            throw EngineException("Reading output '$name' failed: ${e.message}", e)
        }
        return v as? OnnxTensor ?: throw EngineException("Output '$name' is not a tensor")
    }

    // ========================================================== profile

    override suspend fun extractProfile(
        sampleFile: File,
        onProgress: (String) -> Unit,
    ): SpeakerProfile = withContext(Dispatchers.IO) {
        ensureReadyState()
        if (!sampleFile.exists()) throw EngineException("Voice sample not found")
        val stats = melStats ?: throw EngineException("mel_stats.json not loaded")

        onProgress("Analyzing voice sample…")
        // ---------------- cond (style path) ----------------
        val pcm22 = PcmDecoder.decodeFloat(sampleFile, SR_COND, MAX_REF_SEC.toDouble())
        if (pcm22.isEmpty()) throw EngineException("Could not decode the voice sample")

        val chunkLen = SR_COND * CHUNK_SEC
        val minLenD = SR_COND * MIN_CHUNK_SEC.toDouble()   // 7276.5 — Python: len(c) < SR*0.33
        val condDim = 1024
        val condT = 32
        val condChunks = ArrayList<FloatArray>(8)
        var ci = 0
        while (ci < pcm22.size) {
            val end = min(ci + chunkLen, pcm22.size)
            if (end - ci >= minLenD) {
                val chunk = pcm22.copyOfRange(ci, end)
                val mel = Mel.flatten(Mel.melCloningChunk(chunk, stats))   // (1,80,T)
                condChunks.add(
                    runGraph(
                        "style_encoder",
                        mapOf("mel" to floatTensor(mel, longArrayOf(1, 80, (mel.size / 80).toLong()))),
                    ) { result ->
                        val t = result.tensor("cond")
                        val shape = t.info.shape
                        val arr = try {
                            t.value as Array<Array<FloatArray>>
                        } catch (e: OrtException) {
                            throw EngineException("style_encoder output read failed: ${e.message}", e)
                        }
                        if (shape.size != 3 || shape[1] != condDim.toLong() || shape[2] != condT.toLong()) {
                            throw EngineException("style_encoder output shape ${shape.contentToString()} != [1,$condDim,$condT]")
                        }
                        val flat = FloatArray(condDim * condT)
                        for (d in 0 until condDim) {
                            arr[0][d].copyInto(flat, d * condT)
                        }
                        flat
                    },
                )
            }
            ci += chunkLen
        }
        if (condChunks.isEmpty()) throw EngineException("reference audio too short (min 0.33 s)")

        // mean over chunks THEN transpose (1,1024,32) → (1,32,1024)
        val cond = Mel.condMeanTranspose(condChunks)

        // ---------------- spk (speaker path) ----------------
        onProgress("Analyzing voice sample… (speaker)")
        val pcm16 = PcmDecoder.decodeFloat(sampleFile, SR_SPK, MAX_SPK_SEC)
        if (pcm16.isEmpty()) throw EngineException("Could not decode the voice sample (16 kHz)")
        val pre = Mel.preemphasis(pcm16)
        val mel64 = Mel.flatten(Mel.melSpeaker(pre))          // (1,64,T)
        val spk = runGraph(
            "speaker_encoder",
            mapOf("mel" to floatTensor(mel64, longArrayOf(1, 64, (mel64.size / 64).toLong()))),
        ) { result ->
            val t = result.tensor("emb")
            val shape = t.info.shape
            if (shape.size != 3 || shape[1] != 512L) {
                throw EngineException("speaker_encoder output shape ${shape.contentToString()} != [1,512,1]")
            }
            val arr = try {
                t.value as Array<Array<FloatArray>>
            } catch (e: OrtException) {
                throw EngineException("speaker_encoder output read failed: ${e.message}", e)
            }
            // emb is (1,512,1) → arr[0][d][0]; arr[0][0] would be a length-1
            // array (zero-padded by copyOf) — read the 512 values properly.
            FloatArray(512) { d -> arr[0][d][0] }
        }
        onProgress("Voice sample analyzed")
        SpeakerProfile(cond, condT, spk)
    }

    // =========================================================== synthesis

    override suspend fun synthesize(
        profile: SpeakerProfile,
        text: String,
        params: SynthParams,
        outWav: File,
        onProgress: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        ensureReadyState()
        if (!profile.isValid) throw EngineException("Invalid speaker profile")
        val tok = tokenizer ?: throw EngineException("Tokenizer not loaded")
        val gen = ensureGenerator()

        // pipeline: text = " ".join(text.split()).lower()
        val normalized = text.split(Regex("(?U)\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .lowercase()

        val segments = splitSegments(normalized, params.language, tok)
        val pieces = ArrayList<FloatArray>(segments.size)
        var total = 0
        for ((idx, seg) in segments.withIndex()) {
            onProgress(
                if (segments.size > 1) "Synthesizing speech… (${idx + 1}/${segments.size})"
                else "Synthesizing speech…",
            )
            val ids = tok.encode(seg, params.language)
            if (ids.isEmpty()) continue
            val wav = gen.generate(
                ids = ids,
                cond = profile.condLatent,
                spk = profile.speakerEmb,
                speed = params.speed,
                seed = System.nanoTime(),
            ) { pct ->
                onProgress(
                    if (segments.size > 1) "Synthesizing speech… (${idx + 1}/${segments.size}) $pct%"
                    else "Synthesizing speech… $pct%",
                )
            }
            if (wav.isNotEmpty()) {
                pieces.add(wav)
                total += wav.size
            }
        }
        if (total == 0) throw EngineException("Synthesis produced no audio")

        val pcm = FloatArray(total)
        var off = 0
        for (p in pieces) {
            p.copyInto(pcm, off)
            off += p.size
        }
        writeWav16(outWav, pcm, SR_OUT)
        onProgress("Speech ready")
    }

    /**
     * Split [text] so every segment encodes to ≤ [MAX_TEXT_IDS] ids, preferring
     * sentence boundaries (then clauses, words, then halving) — the on-device
     * counterpart of tokenizer.py `split_sentence` + the native length assert.
     */
    internal fun splitSegments(text: String, lang: String, tok: XttsTokenizer): List<String> {
        if (text.isBlank()) return listOf(text)
        if (fits(text, lang, tok)) return listOf(text)
        val levels = listOf(
            Regex("(?U)(?<=[.!?…])\\s+"),
            Regex("(?U)(?<=[,;:])\\s+"),
            Regex("(?U)\\s+"),
        )
        for (splitter in levels) {
            val parts = splitter.split(text).filter { it.isNotBlank() }
            if (parts.size > 1) {
                val packed = pack(parts, lang, tok)
                val out = ArrayList<String>()
                for (p in packed) {
                    if (fits(p, lang, tok)) out.add(p)
                    else out.addAll(splitSegments(p, lang, tok))
                }
                return out
            }
        }
        // single unbreakable blob (a "word" longer than 401 ids): halve until it fits
        var s = text
        var guard = 0
        while (!fits(s, lang, tok) && s.length > 1 && guard < 32) {
            s = s.substring(0, s.length / 2)
            guard++
        }
        return listOf(s)
    }

    private fun fits(text: String, lang: String, tok: XttsTokenizer): Boolean =
        runCatching { tok.encode(text, lang).size <= MAX_TEXT_IDS }.getOrDefault(false)

    private fun pack(parts: List<String>, lang: String, tok: XttsTokenizer): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (p in parts) {
            val candidate = if (sb.isEmpty()) p else "$sb $p"
            if (sb.isEmpty() || fits(candidate, lang, tok)) {
                sb.setLength(0)
                sb.append(candidate)
            } else {
                out.add(sb.toString())
                sb.setLength(0)
                sb.append(p)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    // -------------------------------------------------------------- helpers

    private fun floatTensor(data: FloatArray, shape: LongArray): OnnxTensor = try {
        OnnxTensor.createTensor(ensureEnv(), FloatBuffer.wrap(data), shape)
    } catch (e: OrtException) {
        throw EngineException("Tensor creation failed: ${e.message}", e)
    }

    /** WAV out: 16-bit PCM mono, clipped to [-1, 1] like ffmpeg f32→s16. */
    private fun writeWav16(out: File, pcm: FloatArray, sampleRate: Int) {
        val dataLen = pcm.size * 2
        out.parentFile?.mkdirs()
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
            buf.put("RIFF".toByteArray())
            buf.putInt(36 + dataLen)
            buf.put("WAVE".toByteArray())
            buf.put("fmt ".toByteArray())
            buf.putInt(16)
            buf.putShort(1)                       // PCM
            buf.putShort(1)                       // mono
            buf.putInt(sampleRate)
            buf.putInt(sampleRate * 2)
            buf.putShort(2)
            buf.putShort(16)
            buf.put("data".toByteArray())
            buf.putInt(dataLen)
            for (v in pcm) {
                val c = if (v > 1f) 1f else if (v < -1f) -1f else v
                buf.putShort((c * 32767f).toInt().toShort())
            }
            raf.write(buf.array())
        }
    }

    override fun close() {
        synchronized(sessions) {
            for (s in sessions.values) runCatching { s.close() }
            sessions.clear()
        }
        generator = null
        runCatching { sessionOptions?.close() }
        sessionOptions = null
        runCatching { env?.close() }
        env = null
        ready = false
        tokenizer = null
    }
}
