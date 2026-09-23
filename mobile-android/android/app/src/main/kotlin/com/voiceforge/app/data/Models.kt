package com.voiceforge.app.data

import com.voiceforge.app.engine.SynthParams

/** A stored voice sample (uploaded or recorded), mirrors the Linux app's schema. */
data class VoiceSample(
    val id: String,
    val name: String,
    val file: String,          // file name inside voices/
    val created: Long,         // epoch ms
    val duration: Double,      // seconds
    val source: String = "upload", // upload | record
)

/** A generated MP3 kept in the app's Saved library. */
data class SavedItem(
    val id: String,
    val name: String,
    val file: String,          // file name inside saved/
    val created: Long,         // epoch ms — when it was saved
    val text: String = "",
    val mood: String = "",
    val voiceName: String = "",
    val duration: Double = 0.0,
    val settings: GenSettings? = null,
)

/** Snapshot of the controls used for a generation (for the Regenerate button). */
data class GenSettings(
    val mood: String,
    val pitch: Float,
    val speed: Float,
    val gain: Float,
    val normalize: Boolean,
    val language: String,
    val voiceId: String,
    val voiceName: String,
) {
    fun toParams() = SynthParams(
        language = language,
        mood = mood,
        pitchSemitones = pitch,
        speed = speed,
        gainDb = gain,
        normalize = normalize,
    )
}

enum class QueueStatus { PENDING, RUNNING, DONE, ERROR }

data class QueueItem(
    val id: String,
    val text: String,
    val status: QueueStatus = QueueStatus.PENDING,
    val savedFile: String? = null,   // file name in saved/ once DONE
    val savedAt: Long? = null,
    val error: String? = null,
)

data class AppConfig(
    val lang: String = "en",         // en | pl
    val normalize: Boolean = false,
    val theme: String = "system",    // system | light | dark
)
