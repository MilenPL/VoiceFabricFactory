package com.voiceforge.app.engine.xtts

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round

/**
 * XTTS latent-domain speed change — `F.interpolate(latents, size, mode="linear",
 * align_corners=False)` from the pipeline, kept side-effect-free so the JVM
 * tests validate the very code the device runs.
 */
internal object LatentOps {

    /**
     * src = (i+0.5)·(L/newL) − 0.5 clamped to [0, L−1], then linear lerp —
     * edge behaviour verified empirically against torch (clamp, not zero-fill).
     * Input/output layout: (1, L, dim).
     */
    fun interpolate(latents: Array<Array<FloatArray>>, speed: Float): Array<Array<FloatArray>> {
        val l = latents[0].size
        val dim = latents[0][0].size
        val target = max(1, pythonRound(l * speed.toDouble()))
        if (target == l) return latents
        val src = latents[0]
        val out = arrayOf(Array(target) { FloatArray(dim) })
        val scale = l.toDouble() / target
        for (i in 0 until target) {
            var pos = (i + 0.5) * scale - 0.5
            if (pos < 0.0) pos = 0.0
            if (pos > l - 1) pos = (l - 1).toDouble()
            val i0 = floor(pos).toInt()
            val i1 = if (i0 + 1 < l) i0 + 1 else i0
            val frac = (pos - i0).toFloat()
            val a = src[i0]
            val b = src[i1]
            val dst = out[0][i]
            for (d in 0 until dim) dst[d] = a[d] + (b[d] - a[d]) * frac
        }
        return out
    }

    /** Python `int(round(x))` — round-half-to-even (torch size pick parity). */
    fun pythonRound(x: Double): Int {
        val fl = floor(x)
        val frac = x - fl
        return if (frac == 0.5) {
            val even = fl.toInt()
            if (even % 2 == 0) even else even + 1
        } else {
            round(x).toInt()
        }
    }
}
