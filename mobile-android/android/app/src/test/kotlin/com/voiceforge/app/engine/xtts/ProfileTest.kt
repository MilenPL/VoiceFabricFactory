package com.voiceforge.app.engine.xtts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.voiceforge.app.engine.EngineException
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Full profile-extraction validation: Kotlin mel + desktop ONNX Runtime on
 * the exported `style_encoder`/`speaker_encoder` graphs vs the Python
 * reference (`gen_kotlin_vectors.py`, same code as onnx_check.py stage A/B —
 * which passed at 1.4e-5 / 6.1e-5 against the native model).
 *
 * Tolerances mirror onnx_check's: cond 1e-3, spk 1e-4.
 */
class ProfileTest {

    @Test
    fun extractProfileMatchesPython() {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions()
        opts.setInterOpNumThreads(1)
        opts.setIntraOpNumThreads(4)
        try {
            val modelDir = TestModels.onnxDir()
            env.createSession(File(modelDir, "style_encoder.onnx").absolutePath, opts).use { style ->
                runProfile(style)
            }
        } finally {
            opts.close()
        }
    }

    private fun runProfile(style: OrtSession) {
        val statsArr = org.json.JSONArray(Npy.text("mel_stats.json"))
        val stats = FloatArray(statsArr.length()) { statsArr.getDouble(it).toFloat() }
        val (expectedCond, condShape) = Npy.floats("cond.npy")
        val (expectedSpk, _) = Npy.floats("spk.npy")

        // ---- cond: 6 s chunks of the first 30 s (same as the engine)
        val pcm22 = com.voiceforge.app.audio.PcmDecoder.decodeFloat(refFile(), 22050, 30.0)
        val chunkLen = 22050 * 6
        val minLen = 22050 * 0.33
        val chunks = ArrayList<FloatArray>(8)
        var ci = 0
        while (ci < pcm22.size) {
            val end = minOf(ci + chunkLen, pcm22.size)
            if (end - ci >= minLen) {
                val mel = Mel.flatten(Mel.melCloningChunk(pcm22.copyOfRange(ci, end), stats))
                val feeds = mapOf("mel" to tensor(mel, longArrayOf(1, 80, (mel.size / 80).toLong())))
                style.run(feeds).use { r ->
                    @Suppress("UNCHECKED_CAST")
                    val out = r.get("cond").orElseThrow { EngineException("no cond") }
                        .value as Array<Array<FloatArray>>
                    val flat = FloatArray(1024 * 32)
                    for (d in 0 until 1024) out[0][d].copyInto(flat, d * 32)
                    chunks.add(flat)
                }
            }
            ci += chunkLen
        }
        assertTrue(chunks.isNotEmpty(), "no cond chunks")
        val cond = Mel.condMeanTranspose(chunks)
        assertEquals(condShape.fold(1L) { a, b -> a * b }, cond.size.toLong())
        val condErr = maxAbs(cond, expectedCond)
        println("cond max abs err = $condErr (vs Python/onnx_check reference)")
        assertTrue(condErr < 1e-3f, "cond err $condErr >= 1e-3")

        // ---- spk: preemphasis + mel64 @16k
        val opts2 = OrtSession.SessionOptions()
        opts2.setInterOpNumThreads(1)
        opts2.setIntraOpNumThreads(4)
        try {
            val modelDir = TestModels.onnxDir()
            env().createSession(File(modelDir, "speaker_encoder.onnx").absolutePath, opts2).use { spkSess ->
                val pcm16 = com.voiceforge.app.audio.PcmDecoder.decodeFloat(refFile(), 16000, 600.0)
                val mel64 = Mel.flatten(Mel.melSpeaker(Mel.preemphasis(pcm16)))
                val feeds = mapOf("mel" to tensor(mel64, longArrayOf(1, 64, (mel64.size / 64).toLong())))
                spkSess.run(feeds).use { r ->
                    @Suppress("UNCHECKED_CAST")
                    val out = r.get("emb").orElseThrow { EngineException("no emb") }
                        .value as Array<Array<FloatArray>>
                    // emb is (1,512,1) — arr[0][d][0], NOT arr[0][0] (length 1)
                    val spk = FloatArray(512) { d -> out[0][d][0] }

                    // (1) Device-truth chain: ground-truth mel64 vector (device-style
                    // decode, validated by MelTest) → speaker_encoder must equal the
                    // Kotlin path within 1e-4 (tolerance from onnx_check stage B).
                    val (mel64Vec, melShape) = Npy.floats("mel64.npy")
                    assertEquals((1L * 64 * melShape[2]), mel64Vec.size.toLong())
                    val expectedDevice = spkSess.run(
                        mapOf("mel" to tensor(mel64Vec, longArrayOf(1, 64, melShape[2]))),
                    ).use { re ->
                        @Suppress("UNCHECKED_CAST")
                        (re.get("emb").orElseThrow { EngineException("no emb") }
                            .value as Array<Array<FloatArray>>).let { a ->
                            FloatArray(512) { d -> a[0][d][0] }
                        }
                    }
                    val devErr = maxAbs(spk, expectedDevice)
                    println("spk max abs err vs graph(device mel64 vector) = $devErr")
                    assertTrue(devErr < 1e-4f, "spk err $devErr >= 1e-4 (device truth chain)")

                    // (2) vs the pipe reference vector: the Python pipeline decodes
                    // 16 kHz through ffmpeg (band-limited), the device through a
                    // linear int16 resampler (no anti-alias filter) — measured delta
                    // on this reference clip is 6.65e-2 on the l2-normalized
                    // embedding (mel delta 1.13e-3). Must stay bounded: any OTHER
                    // deviation (feed/read bugs, mel regressions) exceeds this.
                    val pipeErr = maxAbs(spk, expectedSpk)
                    println("spk max abs err vs pipe spk.npy = $pipeErr (resampler delta)")
                    assertTrue(pipeErr < 0.1f, "spk err $pipeErr vs pipe reference")
                }
            }
        } finally {
            opts2.close()
        }
    }

    private fun env(): OrtEnvironment = OrtEnvironment.getEnvironment()

    private fun refFile(): File {
        val f = File.createTempFile("ref", ".wav")
        f.deleteOnExit()
        f.writeBytes(Npy.res("ref.wav"))
        return f
    }

    private fun tensor(data: FloatArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env(), FloatBuffer.wrap(data), shape)

    private fun maxAbs(a: FloatArray, b: FloatArray): Float {
        var m = 0f
        for (i in a.indices) {
            val d = abs(a[i] - b[i])
            if (d > m) m = d
        }
        return m
    }
}
