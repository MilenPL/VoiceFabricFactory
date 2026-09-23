package com.voiceforge.app.engine.xtts

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal .npy reader for the arrays produced by `numpy.save` (float32 '<f4'
 * and int64 '<i8') — just enough to load the Python reference vectors in JVM
 * unit tests. Handles BOTH C- and Fortran-ordered storage: output is always
 * C-order (row-major), matching what Kotlin code produces.
 */
object Npy {
    data class Array(val shape: LongArray, val data: ByteArray) {
        val size: Int get() = shape.fold(1L) { a, b -> a * b }.toInt()
    }

    fun read(bytes: ByteArray): Array {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(6)
        bb.get(magic)
        require((magic[0].toInt() and 0xFF) == 0x93 && magic.decodeToString(1, 6) == "NUMPY") {
            "not an npy file"
        }
        val major = bb.get().toInt() and 0xFF
        bb.get()  // minor version byte (both v1 and v2/v3 layouts)
        val hlen = if (major == 1) {
            (bb.get().toInt() and 0xFF) or ((bb.get().toInt() and 0xFF) shl 8)
        } else {
            (bb.get().toInt() and 0xFF) or ((bb.get().toInt() and 0xFF) shl 8) or
                ((bb.get().toInt() and 0xFF) shl 16) or ((bb.get().toInt() and 0xFF) shl 24)
        }
        val headerBytes = ByteArray(hlen)
        bb.get(headerBytes)
        val header = headerBytes.decodeToString()
        val descr = Regex("'descr':\\s*'([^']+)'").find(header)?.groupValues?.get(1)
            ?: error("no descr in npy header: $header")
        val shapeStr = Regex("'shape':\\s*\\(([^)]*)\\)").find(header)?.groupValues?.get(1)
            ?: error("no shape in npy header: $header")
        val shape = if (shapeStr.isBlank()) longArrayOf() else
            shapeStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                .map { it.toLong() }.toLongArray()
        val fortran = Regex("'fortran_order':\\s*(true|false)", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1).equals("true", ignoreCase = true)
        require(descr == "<f4" || descr == "<i8") { "unsupported descr $descr" }
        val raw = ByteArray(bb.remaining())
        bb.get(raw)

        val itemSize = if (descr == "<f4") 4 else 8
        val data = if (fortran && shape.size > 1) transposeToC(raw, shape, itemSize) else raw
        return Array(shape, data)
    }

    /** Reorder Fortran (column-major) linear data to C (row-major). */
    private fun transposeToC(raw: ByteArray, shape: LongArray, itemSize: Int): ByteArray {
        val rank = shape.size
        // Fortran strides: the FIRST dimension is contiguous (stride 1),
        // NOT the reversed-C pattern — torchaudio's transposed (1, mels, t)
        // views save as fortran_order=True and must be read accordingly.
        val fStride = LongArray(rank)
        fStride[0] = 1
        for (k in 1 until rank) fStride[k] = fStride[k - 1] * shape[k - 1]
        val total = shape.fold(1L) { a, b -> a * b }.toInt()
        val out = ByteArray(raw.size)
        val idx = LongArray(rank)
        for (lin in 0 until total) {
            // C-order multi-index for the destination position
            var rem = lin.toLong()
            for (k in 0 until rank) {
                val strideC = shape.drop(k + 1).fold(1L) { a, b -> a * b }
                idx[k] = rem / strideC
                rem %= strideC
            }
            // Fortran source offset
            var src = 0L
            for (k in 0 until rank) src += idx[k] * fStride[k]
            System.arraycopy(
                raw, (src * itemSize).toInt(),
                out, lin * itemSize, itemSize,
            )
        }
        return out
    }

    fun floats(name: String): Pair<FloatArray, LongArray> {
        val a = read(res(name))
        require(a.data.size % 4 == 0)
        val bb = ByteBuffer.wrap(a.data).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(a.data.size / 4)
        bb.asFloatBuffer().get(out)
        return Pair(out, a.shape)
    }

    fun longs(name: String): Pair<LongArray, LongArray> {
        val a = read(res(name))
        val bb = ByteBuffer.wrap(a.data).order(ByteOrder.LITTLE_ENDIAN)
        val n = a.shape.fold(1L) { x, y -> x * y }.toInt().coerceAtLeast(1)
        val width = a.data.size / n
        val out = LongArray(n)
        when (width) {
            8 -> for (i in out.indices) out[i] = bb.getLong(i * 8)
            4 -> for (i in out.indices) out[i] = bb.getInt(i * 4).toLong()
            else -> error("unsupported int width $width")
        }
        return Pair(out, a.shape)
    }

    fun res(name: String): ByteArray {
        val stream = checkNotNull(Npy::class.java.classLoader.getResourceAsStream("vectors/$name")) {
            "missing test resource vectors/$name"
        }
        return stream.use { it.readBytes() }
    }

    fun text(name: String): String = res(name).decodeToString()
}
