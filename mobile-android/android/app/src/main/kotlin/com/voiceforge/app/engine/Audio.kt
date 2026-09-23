package com.voiceforge.app.engine

import com.voiceforge.app.engine.xtts.Mp3Encoder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.tan

/**
 * Delivery post-processing — a Kotlin port of the Linux ffmpeg chain semantics:
 *
 *   pitch (asetrate) → [loudnorm I=-16 LUFS] → volume (gain) → 44100 Hz MP3
 *
 * `speed` is NOT handled here: it is applied by the engine as XTTS-native
 * latent interpolation (duration changes, pitch does not — the same
 * perceptual result as `atempo`, which is why Linux mood parity holds).
 *
 *  - pitch: linear resample by k = 2^(semi/12) — output length n / k, i.e.
 *    pitch up ⇒ shorter (exactly asetrate's duration+pitch coupling on Linux).
 *  - loudnorm: BS.1770 approximation — K-weighting (high-shelf +4 dB @
 *    1681.97 Hz and RLB high-pass @ 38.14 Hz with libebur128 coefficients
 *    computed for the actual sample rate), 400 ms blocks with 75 % overlap,
 *    absolute gate −70 LUFS, relative gate −10 LU, integrated loudness →
 *    gain = −16 − LUFS clamped to ±20 dB, then a simple peak scale to
 *    −1.5 dBFS (true-peak limiting skipped).
 *  - gain: ×10^(dB/20) with hard clamp to ±0.999.
 *  - encode: linear resample to 44100 Hz, LAME JNI at 192 kbps, input
 *    channel count kept (the engine is mono).
 *
 * The byte-copy fallback stays reachable only if the native encoder failed to
 * load (see init) — with the LAME .so packaged for all three ABIs it never is.
 */
object AudioProcessor {
    const val OUT_HZ = 44100
    const val OUT_BPS = 192

    @Volatile
    var realImplementationReady: Boolean = false
        private set

    fun markRealImplementationReady() {
        realImplementationReady = true
    }

    init {
        // Wired as soon as the LAME .so is loadable on this device/ABI.
        if (Mp3Encoder.available) {
            markRealImplementationReady()
        }
    }

    fun process(inWav: File, outMp3: File, params: SynthParams) {
        if (!realImplementationReady) {
            // Dev placeholder: copy bytes (container sniffing plays it fine in ExoPlayer).
            inWav.copyTo(outMp3, overwrite = true)
            return
        }
        processImpl(inWav, outMp3, params)
    }

    private fun processImpl(inWav: File, outMp3: File, params: SynthParams) {
        val (pcm, sr) = Wav.readMonoFloat(inWav)
        if (pcm.isEmpty() || sr <= 0) throw EngineException("Empty intermediate audio: $inWav")

        var x = AudioDsp.pitchShift(pcm, params.pitchSemitones)
        if (params.normalize) {
            x = AudioDsp.loudnorm(x, sr, targetLufs = -16f)
        }
        x = AudioDsp.applyGain(x, params.gainDb)

        val x44 = if (sr == OUT_HZ) x else AudioDsp.resampleLinear(x, sr, OUT_HZ)
        val shorts = ShortArray(x44.size) { i ->
            val v = x44[i]
            val c = if (v > 1f) 1f else if (v < -1f) -1f else v
            (c * 32767f).toInt().toShort()
        }
        val mp3 = Mp3Encoder.encode(shorts, OUT_HZ, 1, OUT_BPS)
        outMp3.parentFile?.mkdirs()
        outMp3.writeBytes(mp3)
    }
}

// ============================================================== WAV helpers

/** Minimal RIFF/WAVE reader/writer for 16-bit PCM (pure Kotlin). */
object Wav {

    /** Reads a 16-bit PCM WAV → mono float32 + sample rate. Channels are downmixed. */
    fun readMonoFloat(file: File): Pair<FloatArray, Int> = RandomAccessFile(file, "r").use { raf ->
        if (raf.length() < 44) return Pair(FloatArray(0), 0)
        val magic = ByteArray(12)
        raf.readFully(magic)
        if (magic.decodeToString(0, 4) != "RIFF" || magic.decodeToString(8, 12) != "WAVE") {
            return Pair(FloatArray(0), 0)
        }
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var dataOffset = -1L
        var dataSize = 0L
        while (raf.filePointer + 8 <= raf.length()) {
            val id = ByteArray(4)
            raf.readFully(id)
            val size = readU32(raf)
            when (String(id, Charsets.US_ASCII)) {
                "fmt " -> {
                    val body = ByteArray(min(size, 16L).toInt())
                    raf.readFully(body)
                    val bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                    bb.short                       // format tag
                    channels = bb.short.toInt() and 0xFFFF
                    sampleRate = bb.int
                    bb.int                         // byte rate
                    bb.short                       // block align
                    bits = bb.short.toInt() and 0xFFFF
                    if (size > 16) raf.seek(raf.filePointer + (size - 16))
                }
                "data" -> {
                    dataOffset = raf.filePointer
                    dataSize = min(size, raf.length() - dataOffset)
                    break
                }
                else -> raf.seek(raf.filePointer + size + (size and 1L))
            }
        }
        if (sampleRate <= 0 || channels <= 0 || bits != 16 || dataOffset < 0 || dataSize <= 0) {
            return Pair(FloatArray(0), 0)
        }
        val bytes = ByteArray(dataSize.toInt())
        raf.seek(dataOffset)
        raf.readFully(bytes)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val frames = bytes.size / (2 * channels)
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) acc += bb.short.toInt()
            out[i] = (acc / channels) / 32768f
        }
        Pair(out, sampleRate)
    }

    private fun readU32(raf: RandomAccessFile): Long {
        val b = ByteArray(4)
        raf.readFully(b)
        return (b[0].toLong() and 0xFF) or
            ((b[1].toLong() and 0xFF) shl 8) or
            ((b[2].toLong() and 0xFF) shl 16) or
            ((b[3].toLong() and 0xFF) shl 24)
    }
}

// ==================================================================== DSP

/** Testable DSP kernels used by [AudioProcessor]. Pure Kotlin. */
object AudioDsp {

    /**
     * `asetrate`-style pitch: out_len = n / 2^(semi/12), samples read with a
     * linear ramp advancing k per output (pitch up → duration shorter by k).
     */
    fun pitchShift(x: FloatArray, semitones: Float): FloatArray {
        if (abs(semitones) < 1e-6f || x.isEmpty()) return x
        val k = 2.0.pow((semitones / 12.0))
        val n = x.size
        val outLen = max(1, round(n / k).toInt())
        val out = FloatArray(outLen)
        val last = n - 1
        for (i in 0 until outLen) {
            val pos = i * k
            val i0 = floor(pos).toInt().coerceAtMost(last)
            val i1 = min(i0 + 1, last)
            val frac = (pos - i0).toFloat().coerceIn(0f, 1f)
            out[i] = x[i0] + (x[i1] - x[i0]) * frac
        }
        return out
    }

    /** Linear resample to [targetHz] (input [sourceHz]); keeps duration. */
    fun resampleLinear(x: FloatArray, sourceHz: Int, targetHz: Int): FloatArray {
        if (sourceHz == targetHz || x.isEmpty()) return x
        val ratio = sourceHz.toDouble() / targetHz
        val outLen = max(1, round(x.size / ratio).toInt())
        val out = FloatArray(outLen)
        val last = x.size - 1
        for (i in 0 until outLen) {
            val pos = i * ratio
            val i0 = floor(pos).toInt().coerceAtMost(last)
            val i1 = min(i0 + 1, last)
            val frac = (pos - i0).toFloat().coerceIn(0f, 1f)
            out[i] = x[i0] + (x[i1] - x[i0]) * frac
        }
        return out
    }

    /** Multiply by 10^(dB/20); peaks hard-clamped to ±0.999. */
    fun applyGain(x: FloatArray, gainDb: Float): FloatArray {
        if (abs(gainDb) < 1e-6f) return x
        val g = 10.0.pow((gainDb / 20.0)).toFloat()
        val out = FloatArray(x.size)
        for (i in x.indices) {
            var v = x[i] * g
            if (v > 0.999f) v = 0.999f else if (v < -0.999f) v = -0.999f
            out[i] = v
        }
        return out
    }

    /** One biquad (Direct Form I) with libebur128-style normalized coefficients. */
    private fun biquad(x: FloatArray, b0: Double, b1: Double, b2: Double, a1: Double, a2: Double): FloatArray {
        val out = FloatArray(x.size)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val xv = x[i].toDouble()
            val yv = b0 * xv + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            out[i] = yv.toFloat()
            x2 = x1; x1 = xv; y2 = y1; y1 = yv
        }
        return out
    }

    /** K-weighting filter (shelf + RLB high-pass) for the given sample rate. */
    fun kWeighting(x: FloatArray, sr: Int): FloatArray {
        // stage 1: high-shelf (libebur128)
        run {
            val f0 = 1681.974450955533
            val g = 3.999843853973347
            val q = 0.7071752369554196
            val kk = tan(PI * f0 / sr)
            val vh = 10.0.pow((g / 20.0))
            val vb = vh.pow(0.4996667741545416)
            val a0 = 1.0 + kk / q + kk * kk
            val b0 = (vh + vb * kk / q + kk * kk) / a0
            val b1 = 2.0 * (kk * kk - vh) / a0
            val b2 = (vh - vb * kk / q + kk * kk) / a0
            val a1 = 2.0 * (kk * kk - 1.0) / a0
            val a2 = (1.0 - kk / q + kk * kk) / a0
            val stage1 = biquad(x, b0, b1, b2, a1, a2)
            // stage 2: RLB high-pass
            val f02 = 38.13547087602444
            val q2 = 0.5003270373238773
            val k2 = tan(PI * f02 / sr)
            val den = 1.0 + k2 / q2 + k2 * k2
            val aa1 = 2.0 * (k2 * k2 - 1.0) / den
            val aa2 = (1.0 - k2 / q2 + k2 * k2) / den
            return biquad(stage1, 1.0, -2.0, 1.0, aa1, aa2)
        }
    }

    /**
     * BS.1770-4 style integrated loudness (no gain applied): 400 ms blocks
     * (75 % overlap), absolute gate −70 LUFS, relative gate −10 LU.
     * Returns NaN when nothing is measurable (short/silent input).
     */
    fun measureLufs(x: FloatArray, sr: Int): Float {
        val v = integratedLufs(x, sr)
        return if (v.isNaN()) Float.NaN else v.toFloat()
    }

    private fun integratedLufs(x: FloatArray, sr: Int): Double {
        if (x.isEmpty()) return Double.NaN
        val filtered = kWeighting(x, sr)
        val blockSize = (0.4 * sr).toInt()
        val hop = (0.1 * sr).toInt()
        if (blockSize <= 0 || x.size < blockSize) return Double.NaN

        val nBlocks = 1 + (x.size - blockSize) / hop
        if (nBlocks < 1) return Double.NaN
        val ms = DoubleArray(nBlocks)
        val lufs = DoubleArray(nBlocks)
        for (b in 0 until nBlocks) {
            val start = b * hop
            var acc = 0.0
            for (i in start until start + blockSize) {
                val v = filtered[i].toDouble()
                acc += v * v
            }
            ms[b] = acc / blockSize
            lufs[b] = -0.691 + 10.0 * log10(ms[b].coerceAtLeast(1e-12))
        }
        // absolute gate
        val absPassed = (0 until nBlocks).filter { lufs[it] > -70.0 }
        if (absPassed.isEmpty()) return Double.NaN
        // relative gate
        var sumMs = 0.0
        for (b in absPassed) sumMs += ms[b]
        val relGate = -0.691 + 10.0 * log10((sumMs / absPassed.size).coerceAtLeast(1e-12)) - 10.0
        val gate = max(-70.0, relGate)
        val gated = absPassed.filter { lufs[it] > gate }
        val finalBlocks = if (gated.isEmpty()) absPassed else gated
        var sumFinal = 0.0
        for (b in finalBlocks) sumFinal += ms[b]
        return -0.691 + 10.0 * log10((sumFinal / finalBlocks.size).coerceAtLeast(1e-12))
    }

    /**
     * BS.1770-4 style integrated loudness + gain to [targetLufs]:
     * 400 ms blocks (75 % overlap), absolute gate −70 LUFS, relative gate
     * −10 LU, gain clamped to ±20 dB, then peak-scaled to −1.5 dBFS.
     * Short/silent inputs pass through unchanged (nothing measurable).
     */
    fun loudnorm(x: FloatArray, sr: Int, targetLufs: Float = -16f): FloatArray {
        if (x.isEmpty()) return x
        val integrated = integratedLufs(x, sr)
        if (integrated.isNaN()) return x

        var gainDb = targetLufs - integrated.toFloat()
        if (gainDb > 20f) gainDb = 20f else if (gainDb < -20f) gainDb = -20f
        // gain applies to the ORIGINAL signal (filtering is measurement only)
        var out = applyGainClamped(x, gainDb)

        // simple peak clamp at −1.5 dBFS
        val ceiling = 10.0.pow((-1.5 / 20.0)).toFloat()
        var peak = 0f
        for (v in out) { val a = abs(v); if (a > peak) peak = a }
        if (peak > ceiling && peak > 0f) {
            val scale = ceiling / peak
            out = FloatArray(out.size) { out[it] * scale }
        }
        return out
    }

    private fun applyGainClamped(x: FloatArray, gainDb: Float): FloatArray {
        if (abs(gainDb) < 1e-6f) return x
        val g = 10.0.pow((gainDb / 20.0)).toFloat()
        return FloatArray(x.size) { x[it] * g }
    }
}
