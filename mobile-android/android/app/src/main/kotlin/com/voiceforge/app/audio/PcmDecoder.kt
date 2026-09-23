package com.voiceforge.app.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.voiceforge.app.util.Waveform
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes stored audio (WAV / MP3 / M4A …) to mono PCM16, downsampled to
 * [targetHz] and capped at [maxSeconds]. Everything runs synchronously —
 * call it from a background dispatcher.
 *
 * WAV files are parsed directly (fast path, also covers the engine's raw
 * output which is WAV bytes regardless of file extension); anything else
 * goes through MediaExtractor + MediaCodec.
 */
object PcmDecoder {

    const val DEFAULT_HZ = 8000
    const val DEFAULT_SECONDS = 60.0

    /** ~200 bucketed peaks for waveform rendering. */
    fun decodePeaks(file: File, buckets: Int = 200): FloatArray =
        Waveform.peaks(decodePcm(file), buckets)

    /** Mono PCM16 at [targetHz], at most [maxSeconds] long (0 on failure). */
    fun decodePcm(file: File, targetHz: Int = DEFAULT_HZ, maxSeconds: Double = DEFAULT_SECONDS): ShortArray {
        if (!file.exists() || file.length() <= 0L) return ShortArray(0)
        val wav = isRiffWave(file)
        val raw = try {
            if (wav) readWav(file, maxSeconds) else decodeWithCodec(file, maxSeconds)
        } catch (_: Throwable) {
            // Last resort: try the other parser before giving up.
            try {
                if (wav) decodeWithCodec(file, maxSeconds) else readWav(file, maxSeconds)
            } catch (_: Throwable) {
                Raw(ShortArray(0), 0, 0)
            }
        }
        return resample(raw, targetHz, maxSeconds)
    }

    /**
     * Mono float32 in [-1, 1] at [targetHz], capped at [maxSeconds] — used by
     * the XTTS engine for profile extraction (30 s @22050 for the style/cond
     * path, up to 600 s @16000 for the speaker path).
     */
    fun decodeFloat(file: File, targetHz: Int, maxSeconds: Double): FloatArray {
        val pcm = decodePcm(file, targetHz, maxSeconds)
        return FloatArray(pcm.size) { (pcm[it].toInt() / 32768f) }
    }

    // ------------------------------------------------------------ WAV fast path

    private data class Raw(val pcm: ShortArray, val sampleRate: Int, val channels: Int)

    private fun isRiffWave(file: File): Boolean = try {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 12) return false
            val head = ByteArray(12)
            raf.readFully(head)
            head.decodeToString(0, 4) == "RIFF" && head.decodeToString(8, 12) == "WAVE"
        }
    } catch (_: Exception) {
        false
    }

    private fun readWav(file: File, maxSeconds: Double): Raw = RandomAccessFile(file, "r").use { raf ->
        if (raf.length() < 44) return Raw(ShortArray(0), 0, 0)
        raf.seek(0)
        val magic = ByteArray(12)
        raf.readFully(magic)
        require(magic.decodeToString(0, 4) == "RIFF") { "not RIFF" }
        require(magic.decodeToString(8, 12) == "WAVE") { "not WAVE" }

        var sampleRate = 0
        var channels = 0
        var bits = 0
        var dataOffset = -1L
        var dataSize = 0L

        while (raf.filePointer + 8 <= raf.length()) {
            val id = ByteArray(4)
            raf.readFully(id)
            val size = readU32(raf)
            val chunk = String(id, Charsets.US_ASCII)
            when (chunk) {
                "fmt " -> {
                    val body = ByteArray(minOf(size, 16L).toInt())
                    raf.readFully(body)
                    val bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                    bb.short                 // audio format (1 = PCM)
                    channels = bb.short.toInt().and(0xFFFF)
                    sampleRate = bb.int
                    bb.int                    // byte rate
                    bb.short                  // block align
                    bits = bb.short.toInt().and(0xFFFF)
                    if (size > 16) raf.seek(raf.filePointer + (size - 16))
                }
                "data" -> {
                    dataOffset = raf.filePointer
                    dataSize = minOf(size, raf.length() - dataOffset)
                    break
                }
                else -> raf.seek(raf.filePointer + size + (size and 1L))
            }
        }
        require(sampleRate > 0 && channels > 0) { "bad fmt" }
        require(bits == 16) { "only 16-bit PCM supported" }
        require(dataOffset >= 0 && dataSize > 0) { "no data chunk" }

        val byteRate = sampleRate * channels * 2
        val cap = (maxSeconds * byteRate).toLong().coerceAtLeast(2L)
        val bytes = ByteArray(minOf(dataSize, cap).toInt())
        raf.seek(dataOffset)
        raf.readFully(bytes)
        Raw(bytesToShorts(bytes), sampleRate, channels)
    }

    private fun readU32(raf: RandomAccessFile): Long {
        val b = ByteArray(4)
        raf.readFully(b)
        return (b[0].toLong() and 0xFF) or
            ((b[1].toLong() and 0xFF) shl 8) or
            ((b[2].toLong() and 0xFF) shl 16) or
            ((b[3].toLong() and 0xFF) shl 24)
    }

    // ------------------------------------------------------- MediaCodec path

    private fun decodeWithCodec(file: File, maxSeconds: Double): Raw {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var format: MediaFormat? = null
            var mime: String? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    format = f
                    mime = m
                    break
                }
            }
            val trackFormat = format ?: return Raw(ShortArray(0), 0, 0)
            val decoder = MediaCodec.createDecoderByType(mime!!)
            codec = decoder
            decoder.configure(trackFormat, null, null, 0)
            decoder.start()

            var sampleRate = intOf(trackFormat, MediaFormat.KEY_SAMPLE_RATE, 0)
            var channels = intOf(trackFormat, MediaFormat.KEY_CHANNEL_COUNT, 0)
            val out = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var stalled = 0

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)
                        val size = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = decoder.outputFormat
                        sampleRate = intOf(of, MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = intOf(of, MediaFormat.KEY_CHANNEL_COUNT, channels)
                        stalled = 0
                    }
                    outIdx >= 0 -> {
                        val outBuf = decoder.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val chunk = ByteArray(info.size)
                            outBuf.get(chunk)
                            out.write(chunk)
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        stalled = 0
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (sampleRate > 0 && channels > 0) {
                            val cap = (maxSeconds * sampleRate * channels * 2).toLong()
                            if (out.size().toLong() >= cap) outputDone = true
                        }
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        stalled++
                        if (inputDone && stalled > 200) outputDone = true
                    }
                }
            }
            runCatching { decoder.stop() }
            val sr = if (sampleRate > 0) sampleRate else return Raw(ShortArray(0), 0, 0)
            val ch = if (channels > 0) channels else 1
            return Raw(bytesToShorts(out.toByteArray()), sr, ch)
        } finally {
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun intOf(f: MediaFormat, key: String, default: Int): Int =
        if (f.containsKey(key)) f.getInteger(key) else default

    // -------------------------------------------------------------- helpers

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val out = ShortArray(bb.remaining())
        bb.get(out)
        return out
    }

    /** Downmix to mono, then linear-resample to [targetHz], capped at maxSeconds. */
    private fun resample(raw: Raw, targetHz: Int, maxSeconds: Double): ShortArray {
        val pcm = raw.pcm
        if (pcm.isEmpty() || raw.sampleRate <= 0 || raw.channels <= 0) return ShortArray(0)

        val mono = if (raw.channels == 1) pcm else {
            val n = pcm.size / raw.channels
            ShortArray(n) { i ->
                var sum = 0
                for (c in 0 until raw.channels) sum += pcm[i * raw.channels + c].toInt()
                (sum / raw.channels).toShort()
            }
        }
        val limit = (maxSeconds * targetHz).toInt().coerceAtLeast(1)
        if (raw.sampleRate == targetHz) {
            return if (mono.size <= limit) mono else mono.copyOf(limit)
        }
        val outLen = minOf((mono.size.toLong() * targetHz) / raw.sampleRate, limit.toLong()).toInt()
        if (outLen <= 0) return ShortArray(0)
        val ratio = raw.sampleRate.toDouble() / targetHz
        val last = mono.size - 1
        return ShortArray(outLen) { i ->
            val pos = i * ratio
            val i0 = pos.toInt().coerceAtMost(last)
            val i1 = (i0 + 1).coerceAtMost(last)
            // Linear interpolation in the int16 domain, computed in DOUBLE like
            // the Python reference (gen_kotlin_vectors.resample_linear_int16 —
            // "same rounding"): ties must round to EVEN (`int(round(v))`),
            // truncation biases every other sample by 1 LSB and visibly shifts
            // the speaker embedding (measured 4e-2 vs 0 with rounding).
            var frac = pos - i0
            if (frac < 0.0) frac = 0.0 else if (frac > 1.0) frac = 1.0
            val v = mono[i0] + (mono[i1] - mono[i0]) * frac
            val fl = kotlin.math.floor(v)
            val fr = v - fl
            val rounded = when {
                fr > 0.5 -> fl + 1.0
                fr < 0.5 -> fl
                else -> if ((fl.toLong() and 1L) == 0L) fl else fl + 1.0   // half → even
            }
            rounded.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}
