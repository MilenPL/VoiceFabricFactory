package com.voiceforge.app.engine.xtts

/**
 * Model file manifest for the XTTS-v2 ONNX export.
 *
 * NOTE: Kotlin has no `const val Map` — `const` is limited to primitives and
 * String. [URLS] is a plain immutable `val` compiled into the class.
 */
object ModelConfig {

    /**
     * file name → download URL.
     *
     * TODO(owner): hosting is decided later — placeholder URLs for now.
     * If the files are already present in `Storage.modelDir` (e.g. pushed by a
     * test or a future installer), they are used as-is and nothing is
     * downloaded.
     */
    val URLS: Map<String, String> = mapOf(
        "style_encoder.onnx" to url("style_encoder.onnx"),
        "speaker_encoder.onnx" to url("speaker_encoder.onnx"),
        "gpt_prefix.onnx" to url("gpt_prefix.onnx"),
        "gpt_prefill.onnx" to url("gpt_prefill.onnx"),
        "gpt_decode.onnx" to url("gpt_decode.onnx"),
        "gpt_latents.onnx" to url("gpt_latents.onnx"),
        "hifigan_decoder.onnx" to url("hifigan_decoder.onnx"),
        "meta.json" to url("meta.json"),
        "mel_stats.json" to url("mel_stats.json"),
        "vocab.json" to url("vocab.json"),
    )

    private fun url(name: String) = "https://example.invalid/voiceforge/$name"

    /** ONNX session names, in load order. */
    val GRAPHS = listOf(
        "style_encoder",
        "speaker_encoder",
        "gpt_prefix",
        "gpt_prefill",
        "gpt_decode",
        "gpt_latents",
        "hifigan_decoder",
    )

    /** meta.json values the engine needs. */
    data class Meta(
        val startAudioToken: Int,
        val stopAudioToken: Int,
        val maxTextTokens: Int,
        val maxGenMelTokens: Int,
        val codeStrideLen: Int,
        val outputSampleRate: Int,
        val condLen: Int,
    )
}
