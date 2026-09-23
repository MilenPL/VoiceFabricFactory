package com.voiceforge.app.util

import kotlin.math.abs

/** Waveform peak extraction: bucketed max-abs over mono PCM16 samples. */
object Waveform {

    /**
     * Splits [pcm] into [buckets] windows and returns the normalized (0..1)
     * peak amplitude of each window — ready to draw as bars on a Canvas.
     * Empty input yields [buckets] zeros.
     */
    fun peaks(pcm: ShortArray, buckets: Int = 200): FloatArray {
        val n = buckets.coerceAtLeast(1)
        val out = FloatArray(n)
        if (pcm.isEmpty()) return out
        val per = pcm.size.toDouble() / n
        for (b in 0 until n) {
            val from = (b * per).toInt().coerceIn(0, pcm.size - 1)
            val to = ((b + 1) * per).toInt().coerceIn(from, pcm.size)
            var max = 0
            for (i in from until to) {
                val v = abs(pcm[i].toInt())
                if (v > max) max = v
            }
            out[b] = (max / 32767f).coerceIn(0f, 1f)
        }
        return out
    }
}
