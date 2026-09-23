package com.voiceforge.app.data

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * The four Google TTS demo voices shipped in `assets/demo_voices/`.
 *
 * They are installed exactly once — only when the user has no voices at all —
 * so calling [installIfEmpty] on every launch is safe and idempotent.
 * Names (with the en-dash "–") and durations match the desktop app.
 */
object DemoVoices {

    private data class Demo(val asset: String, val name: String, val duration: Double)

    private val DEMOS = listOf(
        Demo("pl.mp3", "Google TTS – Polski", 47.8),
        Demo("en.mp3", "Google TTS – English", 40.2),
        Demo("de.mp3", "Google TTS – Deutsch", 35.7),
        Demo("fr.mp3", "Google TTS – Français", 35.3),
    )

    /** Copy the demo assets into voices/ and register them, if none exist yet. */
    fun installIfEmpty(context: Context) {
        runCatching {
            if (Storage.listVoices().isNotEmpty()) return
            val now = System.currentTimeMillis()
            DEMOS.forEachIndexed { index, demo ->
                val id = UUID.randomUUID().toString().replace("-", "").take(12).lowercase()
                val fileName = "$id.mp3"
                context.assets.open("demo_voices/${demo.asset}").use { input ->
                    File(Storage.voicesDir, fileName).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Storage.addVoice(
                    VoiceSample(
                        id = id,
                        name = demo.name,
                        file = fileName,
                        created = now - index * 86400000L,
                        duration = demo.duration,
                        source = "upload",
                    ),
                )
            }
        }
    }
}
