package com.voiceforge.app.engine.xtts

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tokenizer port validation against the Python reference:
 *  - `vectors.json["tokenizer"]`: `VoiceBpeTokenizer.encode` outputs
 *  - `vectors.json["cleaned"]`: `preprocess_text` (multilingual_cleaners) outputs
 *  - `text_ids.npy`: ids the NATIVE PyTorch model's tokenizer produced
 *    (onnx_check.py stage C — first id 294 for the sample text, i.e. `[pl]`)
 */
class TokenizerTest {

    private val tok: XttsTokenizer by lazy {
        val vocab = File.createTempFile("vocab", ".json")
        vocab.deleteOnExit()
        vocab.writeBytes(Npy.res("vocab.json"))
        XttsTokenizer(vocab)
    }

    private fun vectors(): org.json.JSONObject =
        org.json.JSONObject(Npy.text("vectors.json"))

    @Test
    fun tokenizerIdsMatchPythonExactly() {
        val arr = vectors().getJSONArray("tokenizer")
        var checked = 0
        for (i in 0 until arr.length()) {
            val case = arr.getJSONObject(i)
            val text = case.getString("text")
            val lang = case.getString("lang")
            val ids = tok.encode(text, lang)
            if (case.has("error")) {
                // Python raised — we must raise too (zh/ja/ko not reachable here)
                throw AssertionError("Kotlin accepted text Python rejected: $text")
            }
            val expected = case.getJSONArray("ids")
            val expList = (0 until expected.length()).map { expected.getInt(it) }
            assertEquals(
                expList, ids,
                "ids mismatch lang=$lang text=${text.take(60)}",
            )
            checked++
        }
        assertTrue(checked >= 10, "expected ≥10 tokenizer cases, ran $checked")
    }

    @Test
    fun sampleTextMatchesNativeModelIds() {
        // onnx_check.py's saved ids — produced by the PyTorch model's tokenizer
        val (native, shape) = Npy.longs("text_ids.npy")
        val sample = Npy.text("text.txt").trim()
        val ids = tok.encode(sample, "pl")
        assertEquals(shape[1].toInt(), ids.size, "sample id count")
        assertEquals(294, ids[0], "first token must be [pl] (id 294)")
        for (i in ids.indices) {
            assertEquals(native[i], ids[i].toLong(), "native id[$i]")
        }
    }

    @Test
    fun cleanersMatchPython() {
        val arr = vectors().getJSONArray("cleaned")
        for (i in 0 until arr.length()) {
            val case = arr.getJSONObject(i)
            val lang = case.getString("lang")
            val expected = case.getString("cleaned")
            // preprocess_text is package-private behaviour — exercise it via encode's
            // preprocessing by re-implementing the public path: we call the internal
            // hook directly (same module).
            val got = tok.preprocessText(case.getString("text"), lang)
            assertEquals(expected, got, "cleaned mismatch lang=$lang")
        }
    }

    @Test
    fun sampleTextPreprocessIsIdempotentWithEncoding() {
        // full encode of the task's sample must be 38 ids (native reference)
        val ids = tok.encode(
            "Witaj świecie to jest test polskiej wymowy i klonowania głosu.", "pl",
        )
        assertEquals(38, ids.size)
    }
}
