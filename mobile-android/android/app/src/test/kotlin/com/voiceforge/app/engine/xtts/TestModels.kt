package com.voiceforge.app.engine.xtts

import org.json.JSONObject
import java.io.File

/** Locates the exported ONNX graphs (repo files — far too big for test resources). */
object TestModels {

    /** `tools/onnx_export/onnx_out` — searched upward from the working directory. */
    fun onnxDir(): File {
        System.getProperty("xtts.onnx")?.let { return File(it) }
        var dir: File? = File("").canonicalFile
        while (dir != null) {
            val candidate = File(dir, "tools/onnx_export/onnx_out")
            if (File(candidate, "gpt_prefill.onnx").exists()) return candidate
            dir = dir.parentFile
        }
        error("onnx_out not found (set -Dxtts.onnx=/path/to/onnx_out)")
    }

    fun meta(): ModelConfig.Meta {
        val o = JSONObject(File(onnxDir(), "meta.json").readText())
        return ModelConfig.Meta(
            startAudioToken = o.getInt("start_audio_token"),
            stopAudioToken = o.getInt("stop_audio_token"),
            maxTextTokens = o.getInt("max_text_tokens"),
            maxGenMelTokens = o.getInt("max_gen_mel_tokens"),
            codeStrideLen = o.getInt("code_stride_len"),
            outputSampleRate = o.getInt("output_sample_rate"),
            condLen = o.optInt("cond_len", 32),
        )
    }
}
