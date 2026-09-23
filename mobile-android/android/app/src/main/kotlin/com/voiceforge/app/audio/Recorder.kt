package com.voiceforge.app.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records 16 kHz mono PCM16 into a WAV file with a proper 44-byte header.
 *
 * [start] opens the microphone and spawns a reader thread that appends PCM
 * data after the reserved header; [stop] joins the thread, finalizes the
 * header and returns the recorded duration in seconds. Recording auto-stops
 * at [MAX_SECONDS] (10 minutes).
 */
class Recorder(private val outFile: File) {

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_SECONDS = 600      // hard cap, auto-stop
        const val MIN_SECONDS = 6.0      // XTTS minimum reference length
        private const val CHUNK_SAMPLES = 1600   // 100 ms per read
    }

    @Volatile
    var isRecording: Boolean = false
        private set

    /** Seconds recorded so far (updates ~10 times per second). */
    @Volatile
    var elapsed: Double = 0.0
        private set

    private var thread: Thread? = null
    @Volatile
    private var stopRequested = false

    /** Opens the mic and starts writing. @return false when unavailable. */
    fun start(): Boolean {
        if (isRecording) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return false
        val audio = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, 8192) * 2,
            )
        } catch (_: Exception) {
            return false
        }
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            return false
        }
        val raf = try {
            RandomAccessFile(outFile, "rw").apply {
                setLength(44)          // reserve the WAV header
                seek(44)
            }
        } catch (_: Exception) {
            audio.release()
            return false
        }

        stopRequested = false
        elapsed = 0.0
        isRecording = true
        try {
            audio.startRecording()
        } catch (_: Exception) {
            isRecording = false
            audio.release()
            runCatching { raf.close() }
            outFile.delete()
            return false
        }
        thread = Thread({ recordLoop(audio, raf) }, "voiceforge-recorder").also { it.start() }
        return true
    }

    private fun recordLoop(audio: AudioRecord, raf: RandomAccessFile) {
        val samples = ShortArray(CHUNK_SAMPLES)
        try {
            while (!stopRequested) {
                val read = audio.read(samples, 0, samples.size)
                if (read < 0) break
                if (read > 0) {
                    val bytes = ByteArray(read * 2)
                    for (i in 0 until read) {
                        val s = samples[i].toInt()
                        bytes[2 * i] = (s and 0xFF).toByte()
                        bytes[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
                    }
                    synchronized(raf) { raf.write(bytes) }
                    elapsed += read.toDouble() / SAMPLE_RATE
                    if (elapsed >= MAX_SECONDS) break   // auto-stop at 10:00
                }
            }
        } finally {
            runCatching { audio.stop() }
            audio.release()
            finalizeWav(raf)
            isRecording = false
        }
    }

    /** Stops the capture and returns the recorded duration in seconds. */
    fun stop(): Double {
        stopRequested = true
        val t = thread
        if (t != null) {
            try {
                t.join(3000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        thread = null
        isRecording = false
        return elapsed
    }

    /** Stops (if needed) and deletes the temp file. */
    fun cancel() {
        stop()
        outFile.delete()
    }

    private fun finalizeWav(raf: RandomAccessFile) {
        synchronized(raf) {
            try {
                val dataLen = (raf.length() - 44).coerceAtLeast(0L).toInt()
                raf.seek(0)
                raf.write(wavHeader(dataLen, SAMPLE_RATE))
            } catch (_: Exception) {
                // best effort — a truncated header still leaves the PCM data on disk
            } finally {
                runCatching { raf.close() }
            }
        }
    }

    /** Standard 44-byte canonical WAV header (PCM16, little-endian). */
    fun wavHeader(dataLen: Int, sampleRate: Int, channels: Int = 1, bits: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bits / 8
        val blockAlign = channels * bits / 8
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray())
        h.putInt(36 + dataLen)
        h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray())
        h.putInt(16)
        h.putShort(1)                                  // PCM
        h.putShort(channels.toShort())
        h.putInt(sampleRate)
        h.putInt(byteRate)
        h.putShort(blockAlign.toShort())
        h.putShort(bits.toShort())
        h.put("data".toByteArray())
        h.putInt(dataLen)
        return h.array()
    }
}
