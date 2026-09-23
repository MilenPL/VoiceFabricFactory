package com.voiceforge.app.engine.xtts

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Exact Kotlin port of the two `torchaudio.transforms.MelSpectrogram` variants
 * used by `tools/onnx_export/onnx_pipeline.py`:
 *
 *  - [melCloningChunk] — mel80 (n_fft 2048 / hop 256 / win 1024 periodic hann
 *    CENTERED in the n_fft buffer like `torch.stft`, center reflect-pad,
 *    power 2, fmin 0 / fmax 8000, `norm="slaney"` +
 *    `mel_scale="htk"`, sr 22050) → `log(clamp(min=1e-5)) / mel_stats[b]`.
 *    This mirrors `xtts.get_gpt_cond_latents` (cond/style path).
 *
 *  - [melSpeaker] — mel64 (n_fft 512 / win 400 periodic hamming α=0.54
 *    CENTERED in the n_fft buffer / hop 160, center reflect, power 2,
 *    n_mels 64, sr 16000, NO norm — torchaudio default `norm=None`,
 *    `mel_scale="htk"`) over the preemphasised waveform.
 *    This mirrors the speaker-encoder `torch_spec`.
 *
 * Numerics follow torchaudio 2.x sources 1:1 (see `melscale_fbanks`,
 * `_hz_to_mel`, `_mel_to_hz`, `_create_triangular_filterbank`,
 * `functional.spectrogram`): FFT in double, filterbank math in double,
 * outputs float32 like torch.
 *
 * Pure Kotlin (no android.* imports) so the JVM unit tests can run it.
 */
object Mel {

    const val SR_COND = 22050
    const val SR_SPK = 16000

    // ------------------------------------------------------------ windows

    /** `torch.hann_window(n)` — periodic=True is torch's default. */
    fun hannWindow(n: Int): FloatArray {
        val w = FloatArray(n)
        for (i in 0 until n) w[i] = (0.5 * (1.0 - cos(2.0 * PI * i / n))).toFloat()
        return w
    }

    /** `torch.hamming_window(n)` — periodic=True, alpha=0.54 (torch default). */
    fun hammingWindow(n: Int): FloatArray {
        val w = FloatArray(n)
        for (i in 0 until n) w[i] = (0.54 - 0.46 * cos(2.0 * PI * i / n)).toFloat()
        return w
    }

    // --------------------------------------------------------------- DSP

    /** `torch.nn.functional.pad(x, (l, r), mode="reflect")` (== numpy pad reflect). */
    fun reflectPad(x: FloatArray, left: Int, right: Int): FloatArray {
        require(left < x.size && right < x.size) { "reflect pad must be < input length" }
        val out = FloatArray(x.size + left + right)
        for (i in 0 until left) out[i] = x[left - i]           // y[i] = x[pad - i]
        x.copyInto(out, left)
        for (j in 0 until right) out[left + x.size + j] = x[x.size - 2 - j]
        return out
    }

    /** In-place iterative radix-2 Cooley–Tukey FFT (n must be a power of two). */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == im.size && Integer.bitCount(n) == 1) { "FFT size must be a power of two" }
        // bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang)
            val wIm = sin_(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                val half = len shr 1
                for (k in 0 until half) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + half] * curRe - im[i + k + half] * curIm
                    val vIm = re[i + k + half] * curIm + im[i + k + half] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + half] = uRe - vRe
                    im[i + k + half] = uIm - vIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun sin_(a: Double): Double = kotlin.math.sin(a)

    /**
     * Power spectrogram, mirroring `torchaudio.functional.spectrogram(power=2)`:
     * reflect-pad `nFft/2` on both sides (center=True), window the frames and
     * keep the first `nFft/2 + 1` bins of |FFT|². Returns [nFrames][nFreqs].
     */
    fun powerSpectrogram(x: FloatArray, nFft: Int, hop: Int, win: FloatArray): Array<FloatArray> {
        val pad = nFft / 2
        val padded = reflectPad(x, pad, pad)
        val nFrames = (padded.size - nFft) / hop + 1
        val nFreqs = nFft / 2 + 1
        val frames = Array(nFrames) { FloatArray(nFreqs) }
        val re = DoubleArray(nFft)
        val im = DoubleArray(nFft)
        for (f in 0 until nFrames) {
            val start = f * hop
            for (i in 0 until nFft) {
                re[i] = padded[start + i] * win[i].toDouble()
                im[i] = 0.0
            }
            fft(re, im)
            val out = frames[f]
            for (k in 0 until nFreqs) out[k] = (re[k] * re[k] + im[k] * im[k]).toFloat()
        }
        return frames
    }

    // ------------------------------------------------------- mel machinery

    /** torchaudio `_hz_to_mel` (both "htk" and "slaney" scales). */
    fun hzToMel(hz: Double, slaneyScale: Boolean): Double =
        if (!slaneyScale) {
            2595.0 * kotlin.math.log10(1.0 + hz / 700.0)
        } else {
            val fSp = 200.0 / 3.0
            val minLogHz = 1000.0
            val minLogMel = minLogHz / fSp
            val logstep = ln(6.4) / 27.0
            var mel = hz / fSp
            if (hz >= minLogHz) mel = minLogMel + ln(hz / minLogHz) / logstep
            mel
        }

    /** torchaudio `_mel_to_hz`. */
    fun melToHz(mel: Double, slaneyScale: Boolean): Double =
        if (!slaneyScale) {
            700.0 * (10.0.pow(mel / 2595.0) - 1.0)
        } else {
            val fSp = 200.0 / 3.0
            val minLogHz = 1000.0
            val minLogMel = minLogHz / fSp
            val logstep = ln(6.4) / 27.0
            if (mel >= minLogMel) minLogHz * kotlin.math.exp(logstep * (mel - minLogMel))
            else fSp * mel
        }

    /**
     * torchaudio `melscale_fbanks` → returns the (nFreqs × nMels) matrix,
     * row-major. NOTE: `mel_scale` is "htk" for BOTH mels (torchaudio default);
     * only the AREA normalization ("slaney" `norm`) differs between the two
     * pipelines — that is the [slaneyNorm] flag.
     */
    fun melFbanks(nFft: Int, sampleRate: Int, fMin: Double, fMax: Double, nMels: Int, slaneyNorm: Boolean): Array<FloatArray> {
        val nFreqs = nFft / 2 + 1
        // all_freqs = torch.linspace(0, sample_rate // 2, nFreqs)
        val allFreqs = DoubleArray(nFreqs)
        val top = (sampleRate / 2).toDouble()          // integer division, like torch
        for (i in 0 until nFreqs) allFreqs[i] = top * i / (nFreqs - 1)

        val fMaxClamped = min(fMax, top)
        val mMin = hzToMel(fMin, slaneyScale = false)   // htk scale for m_pts
        val mMax = hzToMel(fMaxClamped, slaneyScale = false)
        val fPts = DoubleArray(nMels + 2)
        for (i in 0 until nMels + 2) {
            val m = mMin + (mMax - mMin) * i / (nMels + 1)   // linspace(m_min, m_max, n_mels+2)
            fPts[i] = melToHz(m, slaneyScale = false)
        }

        // triangular filters (librosa-style slopes, exactly as torchaudio)
        val fb = Array(nFreqs) { FloatArray(nMels) }
        val fDiff = DoubleArray(nMels + 1)
        for (i in 0 until nMels + 1) fDiff[i] = fPts[i + 1] - fPts[i]
        for (k in 0 until nFreqs) {
            val row = fb[k]
            for (m in 0 until nMels) {
                val down = (allFreqs[k] - fPts[m]) / fDiff[m]
                val up = (fPts[m + 2] - allFreqs[k]) / fDiff[m + 1]
                row[m] = max(0.0, min(down, up)).toFloat()
            }
        }
        if (slaneyNorm) {
            // enorm = 2.0 / (f_pts[2:n_mels+2] - f_pts[:n_mels])
            for (m in 0 until nMels) {
                val enorm = 2.0 / (fPts[m + 2] - fPts[m])
                for (k in 0 until nFreqs) fb[k][m] = (fb[k][m] * enorm).toFloat()
            }
        }
        return fb
    }

    /** MelScale.forward: (freq, time) dot fb → transpose → (mels, time). */
    private fun applyFb(spec: Array<FloatArray>, fb: Array<FloatArray>, nMels: Int): Array<FloatArray> {
        val nFreqs = spec[0].size
        val nFrames = spec.size
        val out = Array(nMels) { FloatArray(nFrames) }
        for (m in 0 until nMels) {
            val col = out[m]
            for (f in 0 until nFrames) {
                val specRow = spec[f]
                var acc = 0.0
                for (k in 0 until nFreqs) acc += specRow[k].toDouble() * fb[k][m]
                col[f] = acc.toFloat()
            }
        }
        return out
    }

    /**
     * `mel_cloning_chunk` from the pipeline: mel80 → log(clamp 1e-5) → / mel_stats.
     * Input: mono waveform @22050. Returns [80][frames].
     */
    fun melCloningChunk(x: FloatArray, melStats: FloatArray): Array<FloatArray> {
        val nFft = 2048
        val win = hannWindow(1024)                       // win_length=1024
        val winPadded = FloatArray(nFft)
        // torch.stft pads a win_length<n_fft window CENTERED inside n_fft:
        // offset (2048-1024)/2 = 512 (left-align would shift every frame by
        // 512 samples = 2 hops — verified against the torchaudio vectors).
        win.copyInto(winPadded, (nFft - win.size) / 2)
        val spec = powerSpectrogram(x, nFft, 256, winPadded)
        val fb = melFbanks(nFft, SR_COND, 0.0, 8000.0, 80, slaneyNorm = true)
        val mel = applyFb(spec, fb, 80)
        require(melStats.size >= 80) { "mel_stats must have 80 entries" }
        for (m in 0 until 80) {
            val inv = (1.0 / melStats[m]).toFloat()
            val row = mel[m]
            for (i in row.indices) {
                val v = if (row[i] < 1e-5f) 1e-5f else row[i]
                row[i] = (ln(v.toDouble())).toFloat() * inv
            }
        }
        return mel
    }

    /** Preemphasis from the pipeline: y[0]=x[0]; y[t]=x[t] - 0.97·x[t-1]. */
    fun preemphasis(x: FloatArray, coeff: Float = 0.97f): FloatArray {
        if (x.isEmpty()) return x
        val y = FloatArray(x.size)
        y[0] = x[0]
        for (i in 1 until x.size) y[i] = x[i] - coeff * x[i - 1]
        return y
    }

    /**
     * `mel_speaker` from the pipeline: mel64 over the PREEMPHASISED 16 kHz
     * waveform, defaults (norm=None, htk). Returns [64][frames] (raw power).
     */
    fun melSpeaker(x16kPreemphasised: FloatArray): Array<FloatArray> {
        val nFft = 512
        val win = hammingWindow(400)
        val winPadded = FloatArray(nFft)
        // same centered torch.stft window padding as [melCloningChunk]
        // (offset (512-400)/2 = 56)
        win.copyInto(winPadded, (nFft - win.size) / 2)
        val spec = powerSpectrogram(x16kPreemphasised, nFft, 160, winPadded)
        val fb = melFbanks(nFft, SR_SPK, 0.0, 8000.0, 64, slaneyNorm = false)
        return applyFb(spec, fb, 64)
    }

    // ------------------------------------------------------------- helpers

    /** Flatten [nMels][frames] into the (1, nMels, frames) tensor layout. */
    fun flatten(mel: Array<FloatArray>): FloatArray {
        val nMels = mel.size
        val frames = mel[0].size
        val out = FloatArray(nMels * frames)
        for (m in 0 until nMels) mel[m].copyInto(out, m * frames)
        return out
    }

    /**
     * `np.transpose(np.mean(chunks, axis=0), (0, 2, 1))` from the pipeline:
     * mean over the per-chunk `style_encoder` outputs (each (1,1024,32)
     * flattened) THEN transpose to the (1,32,1024) layout `gpt_prefix` wants.
     */
    fun condMeanTranspose(chunks: List<FloatArray>): FloatArray {
        require(chunks.isNotEmpty()) { "no cond chunks" }
        val condDim = 1024
        val condT = 32
        val sum = FloatArray(condDim * condT)
        for (c in chunks) {
            require(c.size == condDim * condT) { "cond chunk has ${c.size}, expected ${condDim * condT}" }
            for (i in sum.indices) sum[i] += c[i]
        }
        val cond = FloatArray(condT * condDim)
        val inv = 1.0f / chunks.size
        for (d in 0 until condDim) {
            for (t in 0 until condT) {
                cond[t * condDim + d] = sum[d * condT + t] * inv
            }
        }
        return cond
    }

    /** sqrt(n) helper kept for readability in callers. */
    fun rms(x: FloatArray): Float {
        if (x.isEmpty()) return 0f
        var acc = 0.0
        for (v in x) acc += v * v
        return sqrt(acc / x.size).toFloat()
    }
}
