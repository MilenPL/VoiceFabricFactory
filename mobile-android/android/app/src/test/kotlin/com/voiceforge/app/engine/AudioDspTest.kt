package com.voiceforge.app.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DSP behaviour checks (pitch duration coupling, loudness convergence, gain
 * clamps, resampler duration) — pure Kotlin, no device needed.
 */
class AudioDspTest {

    private fun tone(seconds: Double, sr: Int, freq: Double = 220.0, amp: Double = 0.5): FloatArray {
        val n = (seconds * sr).toInt()
        return FloatArray(n) { i ->
            (amp * sin(2 * Math.PI * freq * i / sr)).toFloat()
        }
    }

    @Test
    fun pitchShiftChangesDurationLikeAsetrate() {
        val x = tone(1.0, 24000)
        // +12 semitones → duration halves (asetrate doubles the rate)
        val up = AudioDsp.pitchShift(x, 12f)
        assertTrue(abs(up.size - x.size / 2) <= 1, "+12 st: ${up.size} vs ${x.size / 2}")
        // −12 → doubles
        val down = AudioDsp.pitchShift(x, -12f)
        assertTrue(abs(down.size - x.size * 2) <= 1, "-12 st: ${down.size} vs ${x.size * 2}")
        // 0 → identity
        assertContentEqualsLocal(x, AudioDsp.pitchShift(x, 0f))
        // fractional: +7 st → n / 2^(7/12)
        val k = Math.pow(2.0, 7.0 / 12.0)
        val frac = AudioDsp.pitchShift(x, 7f)
        assertTrue(abs(frac.size - (x.size / k)) <= 1.0, "+7 st length ${frac.size} vs ${x.size / k}")
        // no NaN/Inf introduced
        for (v in up) assertTrue(v.isFinite())
    }

    @Test
    fun resampleKeepsDuration() {
        val x = tone(1.0, 24000)
        val y = AudioDsp.resampleLinear(x, 24000, 44100)
        assertTrue(abs(y.size - 44100) <= 1, "24k→44.1k length ${y.size}")
        val back = AudioDsp.resampleLinear(y, 44100, 24000)
        assertTrue(abs(back.size - 24000) <= 1, "44.1k→24k length ${back.size}")
    }

    @Test
    fun gainScalesAndClamps() {
        val x = floatArrayOf(0.5f, -0.5f, 0.1f)
        val up = AudioDsp.applyGain(x, 6f)   // ×~1.995
        assertTrue(abs(up[0] - 0.5f * 1.9952623f) < 1e-4f, "6 dB scale: ${up[0]}")
        // huge gain clamps to ±0.999
        val clamped = AudioDsp.applyGain(x, 60f)
        assertTrue(clamped.all { abs(it) <= 0.999f }, "clamp")
        assertTrue(abs(clamped[0] - 0.999f) < 1e-6f)
        // 0 dB identity
        assertContentEqualsLocal(x, AudioDsp.applyGain(x, 0f))
    }

    @Test
    fun loudnormConvergesToTarget() {
        // A quiet tone: after loudnorm, the SAME BS.1770 measurement of the
        // output must land near −16 LUFS (within the gate/approximation slack).
        val sr = 44100
        var x = tone(3.0, sr, freq = 997.0, amp = 0.05)
        x = AudioDsp.loudnorm(x, sr, targetLufs = -16f)
        val measured = AudioDsp.measureLufs(x, sr)
        println("loudnorm output measured = $measured LUFS")
        assertTrue(abs(measured - (-16f)) < 1.0f, "measured $measured LUFS not near -16")
        // peak ceiling −1.5 dBFS
        val ceiling = Math.pow(10.0, -1.5 / 20.0).toFloat()
        val peak = x.maxOf { abs(it) }
        assertTrue(peak <= ceiling + 1e-4f, "peak $peak above ceiling $ceiling")
    }

    @Test
    fun loudnormIsIdempotentEnough() {
        // second pass should move loudness by less than 0.5 LU
        val sr = 44100
        val once = AudioDsp.loudnorm(tone(3.0, sr, 440.0, 0.05), sr, -16f)
        val twice = AudioDsp.loudnorm(once, sr, -16f)
        val d = abs(AudioDsp.measureLufs(twice, sr) - AudioDsp.measureLufs(once, sr))
        println("second-pass drift = $d LU")
        assertTrue(d < 0.5f, "second pass drifted $d LU")
    }

    private fun assertContentEqualsLocal(a: FloatArray, b: FloatArray) {
        assertEquals(a.size, b.size, "length")
        for (i in a.indices) assertTrue(abs(a[i] - b[i]) < 1e-6f, "[$i] ${a[i]} != ${b[i]}")
    }
}
