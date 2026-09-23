package com.voiceforge.app.engine.xtts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.voiceforge.app.engine.EngineException
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * THE loop validation: [GptSegmentGenerator] (the exact class the device
 * runs) against the Python reference and the native PyTorch model:
 *
 *  - `codes_native.npy` (onnx_check stage D — greedy codes from the NATIVE
 *    model) must match EXACTLY, stop token (1025) included.
 *  - `codes8.npy` — first 8 greedy steps of the Python pipeline.
 *  - `wav_native.npy` — full native waveform (onnx_check stage G) within
 *    tolerance (native↔python↔Kotlin accumulate ~1e-3 max error per stage).
 *  - speed interpolation + pythonRound vs torch/Python reference vectors.
 *
 * Loads ~4.7 GB of graphs (prefix+prefill+decode+latents+vocoder) — expect
 * this test to take a few minutes.
 */
class GptLoopTest {

    private fun withGenerator(block: (GptSegmentGenerator) -> Unit) {
        val e = OrtEnvironment.getEnvironment()
        val o = OrtSession.SessionOptions().also {
            it.setInterOpNumThreads(1)
            it.setIntraOpNumThreads(4)
        }
        try {
            val dir = TestModels.onnxDir()
            fun s(name: String) = e.createSession(File(dir, "$name.onnx").absolutePath, o)
            s("gpt_prefix").use { prefix ->
                s("gpt_prefill").use { prefill ->
                    s("gpt_decode").use { decode ->
                        s("gpt_latents").use { latents ->
                            s("hifigan_decoder").use { vocoder ->
                                val gen = GptSegmentGenerator(
                                    env = e,
                                    meta = TestModels.meta(),
                                    prefixSession = prefix,
                                    prefillSession = prefill,
                                    decodeSession = decode,
                                    latentsSession = latents,
                                    vocoderSession = vocoder,
                                )
                                block(gen)
                            }
                        }
                    }
                }
            }
        } finally {
            o.close()
        }
    }

    private fun loadIds(): List<Int> {
        val (ids, _) = Npy.longs("text_ids.npy")
        return ids.map { it.toInt() }
    }

    private fun loadCond(): FloatArray = Npy.floats("cond.npy").first
    private fun loadSpk(): FloatArray = Npy.floats("spk.npy").first

    @Test
    fun greedyCodesMatchNativeModelExactly() {
        val ids = loadIds()
        val cond = loadCond()
        val (native, _) = Npy.longs("codes_native.npy")
        val (codes8, _) = Npy.longs("codes8.npy")
        GptSegmentGenerator.testGreedy = true
        try {
            withGenerator { gen ->
                val codes = gen.generateCodes(ids, cond)
                // exact vs the native PyTorch model (120 tokens incl. stop)
                assertContentEquals(
                    native.toList(), codes.toList(),
                    "greedy codes must equal codes_native.npy",
                )
                // first 8 must equal the Python pipeline dump
                assertContentEquals(
                    codes8.toList(), codes.take(8).toList(),
                    "first 8 greedy codes vs Python pipeline",
                )
                // stop token included as the last code
                assertEquals(TestModels.meta().stopAudioToken, codes.last().toInt())
                assertTrue(codes.size <= TestModels.meta().maxGenMelTokens)
            }
        } finally {
            GptSegmentGenerator.testGreedy = false
        }
    }

    @Test
    fun fullSegmentMatchesNativeWaveform() {
        val ids = loadIds()
        val cond = loadCond()
        val spk = loadSpk()
        val (nativeWav, _) = Npy.floats("wav_native.npy")
        GptSegmentGenerator.testGreedy = true
        try {
            withGenerator { gen ->
                val wav = gen.generate(ids, cond, spk, speed = 1.0f, seed = 1L)
                val n = minOf(wav.size, nativeWav.size)
                assertTrue(
                    abs(wav.size - nativeWav.size) <= 16,
                    "wav length ${wav.size} vs native ${nativeWav.size}",
                )
                var err = 0f
                for (i in 0 until n) {
                    val d = abs(wav[i] - nativeWav[i])
                    if (d > err) err = d
                }
                println("full-segment wav max abs err = $err (n=$n)")
                assertTrue(err < 3e-3f, "wav err $err >= 3e-3 vs native model")
            }
        } finally {
            GptSegmentGenerator.testGreedy = false
        }
    }

    @Test
    fun speedInterpolationMatchesTorch() {
        val interp = JSONObject(Npy.text("vectors.json")).getJSONObject("interp")
        for (speedKey in interp.keys()) {
            val speed = speedKey.toFloat()
            // torch tensor (1, newL, dim) → tolist() = [[[row]…]] (batch outer dim)
            val expectedNested = interp.getJSONArray(speedKey).getJSONArray(0)
            val newL = expectedNested.length()
            // input fixture: (1,7,3) = (arange(21)+1)/21
            val input = Array(1) {
                Array(7) { i -> FloatArray(3) { d -> ((i * 3 + d + 1) / 21f) } }
            }
            val got = LatentOps.interpolate(input, speed)
            assertEquals(newL, got[0].size, "newL for speed=$speed")
            for (i in 0 until newL) {
                val row = expectedNested.getJSONArray(i)
                for (d in 0 until row.length()) {
                    val exp = row.getDouble(d).toFloat()
                    assertTrue(
                        abs(got[0][i][d] - exp) < 2e-6f,
                        "interp speed=$speed [$i][$d]: ${got[0][i][d]} != $exp",
                    )
                }
            }
        }
    }

    @Test
    fun pyRoundMatchesPythonVectors() {
        val arr = JSONObject(Npy.text("vectors.json")).getJSONArray("py_round")
        for (i in 0 until arr.length()) {
            val row = arr.getJSONArray(i)
            val x = row.getDouble(0)
            val expected = row.getInt(1)
            assertEquals(expected, GptSegmentGenerator.pythonRound(x), "round($x)")
        }
    }

    /**
     * gpt_prefix: text_ids + cond → prefix (1, 32+T+2, 1024) must match
     * `prefix.npy` (Python pipeline, computed from the same text_ids/cond
     * vectors) within 1e-4. The prefix is `[cond, emb(text)]` — the cond
     * positions pass through exactly, so this anchors cond too. The first
     * greedy code (codes8 above) anchors the prefill logits; there is no
     * separate logits vector.
     */
    @Test
    fun prefixMatchesPythonVector() {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().also {
            it.setInterOpNumThreads(1)
            it.setIntraOpNumThreads(4)
        }
        try {
            val dir = TestModels.onnxDir()
            env.createSession(File(dir, "gpt_prefix.onnx").absolutePath, opts).use { sess ->
                val (ids, idsShape) = Npy.longs("text_ids.npy")
                val (cond, _) = Npy.floats("cond.npy")
                val (expected, expShape) = Npy.floats("prefix.npy")
                val textTensor = OnnxTensor.createTensor(
                    env, java.nio.LongBuffer.wrap(ids), longArrayOf(idsShape[0], idsShape[1]),
                )
                val condTensor = OnnxTensor.createTensor(
                    env, java.nio.FloatBuffer.wrap(cond), longArrayOf(1, 32, 1024),
                )
                mapOf("text_ids" to textTensor, "cond" to condTensor).let { feeds ->
                    sess.run(feeds).use { r ->
                        @Suppress("UNCHECKED_CAST")
                        val got = r.get("prefix").orElseThrow { EngineException("no prefix") }
                            .value as Array<Array<FloatArray>>
                        val p = got[0].size
                        assertEquals(expShape.fold(1L) { a, b -> a * b }, (p * got[0][0].size).toLong())
                        var err = 0f
                        for (i in 0 until p) {
                            val row = got[0][i]
                            for (d in row.indices) {
                                val diff = kotlin.math.abs(row[d] - expected[i * row.size + d])
                                if (diff > err) err = diff
                            }
                        }
                        println("prefix max abs err = $err (P=$p)")
                        assertTrue(err < 1e-4f, "prefix err $err >= 1e-4")
                    }
                }
            }
        } finally {
            opts.close()
        }
    }
}
