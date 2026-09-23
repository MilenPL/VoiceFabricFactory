package com.voiceforge.app.engine.xtts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.voiceforge.app.engine.EngineException
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Random
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round

/**
 * The autoregressive part of the pipeline — a 1:1 port of
 * `OnnxPipeline.generate_codes` + the latent/vocoder tail of
 * `OnnxPipeline.synthesize`, isolated from Android so the JVM unit tests run
 * EXACTLY the code the device runs (validated against the Python reference
 * and the native PyTorch model via the test_vectors npy dumps).
 *
 * prefix → prefill(ones(1,P+1), last=start_audio_token) → sampling loop
 * (HF repetition penalty over ALL ids seen, temperature → top_k → top_p →
 * softmax → seeded random; greedy = argmax before the warpers) feeding
 * decode with the KV cache → stop_audio_token INCLUDED in the codes, capped
 * at `max_gen_mel_tokens` (602) → gpt_latents (wav_len = codes·1024) →
 * optional speed interpolation of the latents → hifigan_decoder → float32
 * 24 kHz waveform.
 *
 * Feeds are built from each graph's REAL input names (the exported prefill
 * graph prunes `audio_pos_idx`; decode keeps `prefix_emb` + `audio_pos_idx` —
 * every input is mapped explicitly, unknown names fail loudly).
 */
internal class GptSegmentGenerator(
    private val env: OrtEnvironment,
    private val meta: ModelConfig.Meta,
    prefixSession: OrtSession,
    private val prefillSession: OrtSession,
    private val decodeSession: OrtSession,
    private val latentsSession: OrtSession,
    private val vocoderSession: OrtSession,
) {
    private val prefixSession = prefixSession

    /** HF defaults from the reference pipeline. */
    var repetitionPenalty = 10.0f
    var temperature = 0.75f
    var topK = 50
    var topP = 0.85f

    // ================================================================ public

    /**
     * Codes-only run (prefix → prefill → sample loop). Greedy mode with the
     * fixed reference inputs reproduces `codes_native.npy` bit-exactly.
     */
    fun generateCodes(
        ids: List<Int>,
        cond: FloatArray,
        seed: Long = 1L,
        onProgress: (Int) -> Unit = {},
    ): LongArray {
        val (prefix, p) = runPrefix(ids, cond)
        return runCodes(prefix, p, seed, onProgress)
    }

    /**
     * @param ids text token ids (≤ 401)
     * @param cond flattened (1,32,1024) conditioning
     * @param spk (1,512,1) speaker embedding
     * @return float32 mono 24 kHz waveform
     */
    fun generate(
        ids: List<Int>,
        cond: FloatArray,
        spk: FloatArray,
        speed: Float,
        seed: Long,
        onProgress: (Int) -> Unit = {},
    ): FloatArray {
        if (ids.isEmpty()) throw EngineException("empty text ids")
        if (cond.size != 32 * 1024) throw EngineException("cond has ${cond.size} values, expected ${32 * 1024}")
        val textIds = LongArray(ids.size) { ids[it].toLong() }

        // ---- gpt_prefix: text_ids(1,T) + cond(1,32,1024) → prefix(1,P,1024)
        val (prefix, p) = runPrefix(ids, cond)
        val codes = runCodes(prefix, p, seed, onProgress)

        // ---- gpt_latents
        val wavLen = codes.size.toLong() * meta.codeStrideLen
        var latents = runGraph(latentsSession, "gpt_latents", mapOf(
            "text_tokens" to longTensor(textIds, longArrayOf(1, textIds.size.toLong())),
            "text_len" to longTensor(longArrayOf(textIds.size.toLong()), longArrayOf(1)),
            "audio_codes" to longTensor(codes, longArrayOf(1, codes.size.toLong())),
            "wav_len" to longTensor(longArrayOf(wavLen), longArrayOf(1)),
            "cond" to floatTensor(cond, longArrayOf(1, 32, 1024)),
        )) { r ->
            val t = requireOutput(r, "latents")
            t.value3()
        }

        // ---- speed: F.interpolate(linear, align_corners=False) along L
        if (kotlin.math.abs(speed - 1.0f) > 1e-3f) {
            latents = LatentOps.interpolate(latents, speed)
        }

        // ---- hifigan_decoder
        return runGraph(vocoderSession, "hifigan_decoder", mapOf(
            "latents" to nestedTensor(latents),
            "spk" to floatTensor(spk, longArrayOf(1, 512, 1)),
        )) { r ->
            val t = requireOutput(r, "wav")
            try {
                (t.value as Array<Array<FloatArray>>)[0][0]
            } catch (e: OrtException) {
                throw EngineException("hifigan_decoder wav read failed: ${e.message}", e)
            }
        }
    }

    // ============================================================== the loop

    /** gpt_prefix: text_ids(1,T) + cond(1,32,1024) → (prefix, P). */
    private fun runPrefix(ids: List<Int>, cond: FloatArray): Pair<Array<Array<FloatArray>>, Int> {
        if (ids.isEmpty()) throw EngineException("empty text ids")
        if (cond.size != 32 * 1024) throw EngineException("cond has ${cond.size} values, expected ${32 * 1024}")
        val textIds = LongArray(ids.size) { ids[it].toLong() }
        val prefix = runGraph(prefixSession, "gpt_prefix", mapOf(
            "text_ids" to longTensor(textIds, longArrayOf(1, ids.size.toLong())),
            "cond" to floatTensor(cond, longArrayOf(1, 32, 1024)),
        )) { r ->
            requireOutput(r, "prefix").value3()
        }
        return Pair(prefix, prefix[0].size)   // P = 32 + T + 2
    }

    private fun runCodes(
        prefix: Array<Array<FloatArray>>,
        p: Int,
        seed: Long,
        onProgress: (Int) -> Unit,
    ): LongArray {
        val rng = Random(seed)
        val greedy = testGreedy

        // ---- prefill: input_ids = ones(1,P+1), last = start_audio_token
        val inputIds = LongArray(p + 1) { 1L }
        inputIds[p] = meta.startAudioToken.toLong()
        val allIds = ArrayList<Int>(p + 603)
        repeat(p + 1) { allIds.add(1) }
        allIds[p] = meta.startAudioToken

        val prefillFeeds = HashMap<String, OnnxTensor>()
        prefillFeeds["input_ids"] = longTensor(inputIds, longArrayOf(1, (p + 1).toLong()))
        prefillFeeds["prefix_emb"] = nestedTensor(prefix)
        for (name in prefillSession.inputNames) {
            if (name in prefillFeeds) continue
            when {
                name == "audio_pos_idx" ->
                    prefillFeeds[name] = longTensor(longArrayOf(0L), longArrayOf(1))
                name.startsWith("past_") ->
                    prefillFeeds[name] = zerosForPast(prefillSession, name)
                else -> throw EngineException("gpt_prefill: unexpected input '$name'")
            }
        }

        var result: OrtSession.Result
        try {
            result = prefillSession.runChecked(prefillFeeds, "gpt_prefill")
        } catch (e: Exception) {
            for (t in prefillFeeds.values) runCatching { t.close() }
            throw e
        }

        val generated = ArrayList<Int>(256)
        try {
            var logits = lastLogits(result)
            while (generated.size < meta.maxGenMelTokens) {
                val tokId = sampleToken(
                    logits, repetitionPenalty, temperature, topK, topP,
                    allIds.toIntArray(), greedy, rng,
                )
                generated.add(tokId)
                allIds.add(tokId)
                if (tokId == meta.stopAudioToken) break   // included in codes, like HF

                val stepFeeds = HashMap<String, OnnxTensor>()
                val owned = ArrayList<OnnxTensor>(2)
                stepFeeds["input_ids"] =
                    longTensor(longArrayOf(tokId.toLong()), longArrayOf(1, 1)).also { owned.add(it) }
                for (name in decodeSession.inputNames) {
                    if (name in stepFeeds) continue
                    when {
                        // decode keeps prefix_emb (export adds a 0-dependency);
                        // feed the same prefix tensor like Python _decode_feeds
                        name == "prefix_emb" -> stepFeeds[name] =
                            nestedTensor(prefix).also { owned.add(it) }
                        name == "audio_pos_idx" -> stepFeeds[name] =
                            longTensor(longArrayOf(generated.size.toLong()), longArrayOf(1))
                                .also { owned.add(it) }
                        name.startsWith("past_") -> {
                            val idx = name.substring("past_".length)
                            stepFeeds[name] = result.tensor("present_$idx")
                        }
                        else -> throw EngineException("gpt_decode: unexpected input '$name'")
                    }
                }
                val prev = result
                result = try {
                    decodeSession.runChecked(stepFeeds, "gpt_decode")
                } catch (e: Exception) {
                    for (t in owned) runCatching { t.close() }
                    throw e
                }
                stepFeeds.clear()
                for (t in owned) runCatching { t.close() }
                runCatching { prev.close() }
                logits = lastLogits(result)
                if (generated.size % 32 == 0) {
                    onProgress(generated.size * 100 / meta.maxGenMelTokens)
                }
            }
        } finally {
            runCatching { result.close() }
        }
        onProgress(100)
        return LongArray(generated.size) { generated[it].toLong() }
    }

    // ============================================================= sampling

    /**
     * HF sampling exactly as `_sample` in onnx_pipeline.py: repetition penalty
     * over ALL ids seen so far (gathered > 0 → /p else *p), greedy = argmax
     * (no warpers), else temperature → top_k → top_p → softmax → seeded pick.
     */
    fun sampleToken(
        logits: FloatArray,
        penalty: Float,
        temperature: Float,
        topK: Int,
        topP: Float,
        allIds: IntArray,
        greedy: Boolean,
        rng: Random,
    ): Int {
        val s = logits.copyOf()
        val vocab = s.size
        // HF semantics: the penalty is computed from the ORIGINAL scores for
        // every gathered position (s = logits.clone(); gathered = s[idx]) —
        // duplicates in allIds (id 1 appears P+1 times!) must NOT compound:
        // apply it exactly once per position, reading `logits`, not `s`.
        for (id in allIds) {
            if (id < 0 || id >= vocab) continue
            val g = logits[id]
            s[id] = if (g > 0) g / penalty else g * penalty
        }
        if (greedy) return argmax(s)

        for (i in s.indices) s[i] = s[i] / temperature

        if (topK > 0) {
            val k = kotlin.math.min(topK, vocab)
            val sorted = s.copyOf().sortedArrayDescending()
            val kth = sorted[k - 1]
            for (i in s.indices) if (s[i] < kth) s[i] = Float.NEGATIVE_INFINITY
        }
        if (topP < 1.0f) {
            val order = (0 until vocab).sortedByDescending { s[it] }
            var maxV = Float.NEGATIVE_INFINITY
            for (i in order) if (s[i] > maxV) maxV = s[i]
            var sum = 0.0
            val sortedProbs = DoubleArray(vocab)
            for (pos in order.indices) {
                val e = kotlin.math.exp((s[order[pos]] - maxV).toDouble())
                sortedProbs[pos] = e
                sum += e
            }
            for (pos in sortedProbs.indices) sortedProbs[pos] /= sum
            var cum = 0.0
            val masked = FloatArray(vocab) { Float.NEGATIVE_INFINITY }
            for (pos in 0 until vocab) {
                val prob = sortedProbs[pos]
                if (cum - prob <= topP) masked[order[pos]] = s[order[pos]]
                cum += prob
            }
            masked.copyInto(s)
        }
        // softmax + seeded random pick
        var maxV = Float.NEGATIVE_INFINITY
        for (v in s) if (v > maxV) maxV = v
        var sum = 0.0
        val probs = DoubleArray(vocab)
        for (i in s.indices) {
            val e = kotlin.math.exp((s[i] - maxV).toDouble())
            probs[i] = e
            sum += e
        }
        if (sum.isNaN() || sum <= 0.0) return argmax(logits)   // unreachable for topP<1
        var r = rng.nextDouble() * sum
        for (i in probs.indices) {
            r -= probs[i]
            if (r <= 0.0) return i
        }
        return vocab - 1
    }

    private fun argmax(a: FloatArray): Int {
        var best = 0
        var bestV = Float.NEGATIVE_INFINITY
        for (i in a.indices) if (a[i] > bestV) {
            bestV = a[i]
            best = i
        }
        return best
    }

    // ==================================================== speed interpolation
    // (LatentOps.interpolate — shared with the JVM tests — does the work)

    companion object {
        /** Greedy-argmax hook for deterministic unit checks (never set by the UI). */
        @Volatile
        var testGreedy: Boolean = false

        /** Delegates to [LatentOps] (shared with the JVM tests). */
        fun pythonRound(x: Double): Int = LatentOps.pythonRound(x)
    }

    // ================================================================ helpers

    private fun OrtSession.runChecked(feeds: Map<String, OnnxTensor>, name: String): OrtSession.Result =
        try {
            run(feeds)
        } catch (e: OrtException) {
            throw EngineException("$name inference failed: ${e.message}", e)
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

    private fun requireOutput(result: OrtSession.Result, name: String): OnnxTensor = result.tensor(name)

    private fun <T> runGraph(
        session: OrtSession,
        name: String,
        feeds: Map<String, OnnxTensor>,
        block: (OrtSession.Result) -> T,
    ): T {
        try {
            session.run(feeds).use { result -> return block(result) }
        } catch (e: OrtException) {
            throw EngineException("$name inference failed: ${e.message}", e)
        }
    }

    private fun lastLogits(result: OrtSession.Result): FloatArray {
        val t = result.tensor("logits")
        val arr = t.value3()
        return arr[0][arr[0].size - 1]
    }

    private fun OnnxTensor.value3(): Array<Array<FloatArray>> = try {
        value as Array<Array<FloatArray>>
    } catch (e: OrtException) {
        throw EngineException("tensor read failed: ${e.message}", e)
    }

    private fun zerosForPast(s: OrtSession, name: String): OnnxTensor {
        // Static shape of the exported graph: [1, 16, 0, 64] (30 layers × K/V,
        // 16 heads, head_dim 64) — verified against gpt_prefill.onnx. Graph
        // metadata is tried first; the constants are a defensive fallback.
        val fallback = longArrayOf(1, 16, 0, 64)
        val shape = try {
            val info = s.inputInfo[name]?.info as? TensorInfo
            val sh = info?.shape
            if (sh != null && sh.size == 4 && sh.all { it >= 0 }) sh else fallback
        } catch (_: Exception) {
            fallback
        }
        return floatTensor(FloatArray(shape.fold(1L) { a, b -> a * b }.toInt()), shape)
    }

    private fun floatTensor(data: FloatArray, shape: LongArray): OnnxTensor = try {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
    } catch (e: OrtException) {
        throw EngineException("Tensor creation failed: ${e.message}", e)
    }

    private fun longTensor(data: LongArray, shape: LongArray): OnnxTensor = try {
        OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)
    } catch (e: OrtException) {
        throw EngineException("Tensor creation failed: ${e.message}", e)
    }

    /** Tensor from a nested Java array (shape inferred by ONNX Runtime). */
    private fun nestedTensor(data: Any): OnnxTensor = try {
        OnnxTensor.createTensor(env, data)
    } catch (e: OrtException) {
        throw EngineException("Tensor creation failed: ${e.message}", e)
    }
}
