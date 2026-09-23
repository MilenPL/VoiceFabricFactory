package com.voiceforge.app.util

import android.media.MediaMetadataRetriever
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Time / duration helpers shared by every screen (same formats as the Linux
 * app, dates rendered as dd.MM.yyyy HH:mm on Android).
 */
object TimeFmt {

    /** "1:05" (m:ss); "–:––" when the duration is unknown (0). */
    fun duration(sec: Double): String {
        if (sec <= 0.0) return "–:––"
        val total = Math.round(sec).toInt()
        return "${total / 60}:${pad2(total % 60)}"
    }

    /** "0:03 / 1:12" for the result player row. */
    fun position(posMs: Long, durMs: Long): String = "${ms(posMs)} / ${ms(durMs)}"

    /** "00:07" for the record timer. */
    fun clock(sec: Double): String {
        val s = sec.coerceAtLeast(0.0).toInt()
        return "${pad2(s / 60)}:${pad2(s % 60)}"
    }

    /** "dd.MM.yyyy HH:mm". */
    fun dateTime(ts: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(ts))

    private fun ms(v: Long): String {
        val s = v.coerceAtLeast(0L) / 1000
        return "${s / 60}:${pad2((s % 60).toInt())}"
    }

    private fun pad2(v: Int): String = String.format(Locale.ROOT, "%02d", v)

    // ------------------------------------------------- duration probing
    private val cache = HashMap<String, Double>()

    /**
     * Media duration in seconds via [MediaMetadataRetriever]; 0.0 when it
     * cannot be determined. Cached in memory per path + modification time so
     * listing a directory probes every file only once.
     */
    @Synchronized
    fun probeDuration(file: File): Double {
        val key = "${file.absolutePath}:${file.length()}"
        cache[key]?.let { return it }
        val value = readDuration(file)
        cache[key] = value
        return value
    }

    private fun readDuration(file: File): Double {
        if (!file.exists() || file.length() <= 0L) return 0.0
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.let { it / 1000.0 }
                ?: 0.0
        } catch (_: Exception) {
            0.0
        } finally {
            runCatching { mmr.release() }
        }
    }
}
