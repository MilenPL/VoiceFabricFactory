package com.voiceforge.app.engine.xtts

import com.voiceforge.app.audio.PcmDecoder
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mel port validation: Kotlin [Mel] vs torchaudio reference vectors
 * (mel80_c0/c1 = cond path chunks, mel64 = speaker path) computed by
 * `tools/onnx_export/gen_kotlin_vectors.py` from the SAME ref.wav the
 * onnx_check.py run uses.
 *
 * The audio is decoded through [PcmDecoder] exactly like the device does
 * (WAV fast-path + linear resampler).
 */
class MelTest {

    private fun stats(): FloatArray {
        val arr = org.json.JSONArray(Npy.text("mel_stats.json"))
        return FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
    }

    private fun refFile(): File {
        val f = File.createTempFile("ref", ".wav")
        f.deleteOnExit()
        f.writeBytes(Npy.res("ref.wav"))
        return f
    }

    private fun maxAbs(a: FloatArray, b: FloatArray): Float {
        var m = 0f
        for (i in a.indices) {
            val d = abs(a[i] - b[i])
            if (d > m) m = d
        }
        return m
    }

    @Test
    fun mel80MatchesTorch() {
        val (expected0, shape0) = Npy.floats("mel80_c0.npy")
        val (expected1, shape1) = Npy.floats("mel80_c1.npy")

        val pcm = PcmDecoder.decodeFloat(refFile(), Mel.SR_COND, 30.0)
        val chunkLen = Mel.SR_COND * 6
        val minLen = Mel.SR_COND * 0.33
        val mels = ArrayList<Array<FloatArray>>()
        var ci = 0
        while (ci < pcm.size) {
            val end = minOf(ci + chunkLen, pcm.size)
            if (end - ci >= minLen) {
                mels.add(Mel.melCloningChunk(pcm.copyOfRange(ci, end), stats()))
            }
            ci += chunkLen
        }
        assertTrue(mels.size >= 2, "expected ≥2 chunks, got ${mels.size}")

        val flat0 = Mel.flatten(mels[0])
        val frames0 = shape0[2].toInt()
        assertEquals(80L * frames0, flat0.size.toLong(), "chunk0 frame count")
        val err0 = maxAbs(flat0, expected0)

        val flat1 = Mel.flatten(mels[mels.size - 1])
        val frames1 = shape1[2].toInt()
        assertEquals(80L * frames1, flat1.size.toLong(), "tail frame count")
        val err1 = maxAbs(flat1, expected1)

        println("mel80 err: chunk0=$err0 tail=$err1")
        assertTrue(err0 < 2e-3f, "mel80 chunk0 max abs err $err0 >= 2e-3")
        assertTrue(err1 < 2e-3f, "mel80 tail max abs err $err1 >= 2e-3")
    }

    @Test
    fun mel64MatchesTorch() {
        val (expected, shape) = Npy.floats("mel64.npy")
        val pcm = PcmDecoder.decodeFloat(refFile(), Mel.SR_SPK, 600.0)
        val pre = Mel.preemphasis(pcm)
        val mel = Mel.flatten(Mel.melSpeaker(pre))
        assertEquals(shape[1] * shape[2], mel.size.toLong(), "mel64 frame count")
        val err = maxAbs(mel, expected)
        var refMax = 0f
        for (v in expected) if (abs(v) > refMax) refMax = abs(v)
        println("mel64 max abs err=$err (ref max=$refMax)")
        assertTrue(err <= 1e-3f + 1e-5f * refMax, "mel64 max abs err $err too large (refMax=$refMax)")
    }

    @Test
    fun preemphasisMatchesPython() {
        // y[0]=x[0]; y[t]=x[t]-0.97x[t-1]
        val x = floatArrayOf(0.5f, 1.0f, -0.25f, 0.75f)
        val y = Mel.preemphasis(x)
        assertEquals(x[0], y[0])
        for (i in 1 until x.size) {
            val expect = x[i] - 0.97f * x[i - 1]
            assertTrue(abs(y[i] - expect) < 1e-6f, "preemph[$i]")
        }
    }

    @Test
    fun condMeanTransposeMatchesNumpy() {
        // mean over chunks then transpose — verified against numpy by construction:
        // build two synthetic (1,1024,32) chunks with known values.
        val c0 = FloatArray(1024 * 32) { i -> (i % 32).toFloat() }
        val c1 = FloatArray(1024 * 32) { i -> (i % 32) * 3f }
        val out = Mel.condMeanTranspose(listOf(c0, c1))
        // mean[d*32+t] = (t + 3t)/2 = 2t → out[t*1024+d] = 2t (independent of d)
        for (t in 0 until 32) {
            assertTrue(abs(out[t * 1024 + 7] - 2f * t) < 1e-5f, "cond[$t]")
        }
    }
}
