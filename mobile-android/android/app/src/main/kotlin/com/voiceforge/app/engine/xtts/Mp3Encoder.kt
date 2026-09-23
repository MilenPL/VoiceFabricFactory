package com.voiceforge.app.engine.xtts

import com.voiceforge.app.engine.EngineException

/**
 * MP3 encoding through LAME 3.100 via JNI (`libvoiceforge.so`, built from
 * `app/src/main/cpp/lame_jni.c` + static `libmp3lame.a` per ABI).
 *
 * [available] reports whether the native library could be loaded on this
 * device — [com.voiceforge.app.engine.AudioProcessor] keys its
 * real-implementation flag off it.
 */
object Mp3Encoder {

    /** True when libvoiceforge.so (LAME) loaded successfully. */
    val available: Boolean by lazy {
        runCatching {
            System.loadLibrary("voiceforge")
            true
        }.getOrElse { false }
    }

    /**
     * One-shot PCM16 → MP3 encode.
     *
     * @param pcm interleaved PCM16 samples
     * @param sampleRate e.g. 44100
     * @param channels 1 (mono, the engine default) or 2
     * @param kbps constant bitrate, e.g. 192
     * @return complete MP3 byte stream (with headers)
     */
    fun encode(pcm: ShortArray, sampleRate: Int, channels: Int, kbps: Int): ByteArray {
        if (!available) {
            throw EngineException("MP3 encoder (LAME) native library is not available")
        }
        if (pcm.isEmpty()) return ByteArray(0)
        // UnsatisfiedLinkError is an Error, not an Exception — wrap it so a
        // symbol/ABI problem surfaces as a readable engine error instead of
        // crashing the generation flow.
        return try {
            encodeNative(pcm, sampleRate, channels, kbps)
        } catch (e: UnsatisfiedLinkError) {
            throw EngineException("MP3 encoder native call failed: ${e.message}", e)
        }
    }

    @JvmStatic
    private external fun encodeNative(pcm: ShortArray, sampleRate: Int, channels: Int, kbps: Int): ByteArray
}
