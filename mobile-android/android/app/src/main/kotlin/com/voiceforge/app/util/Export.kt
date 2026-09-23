package com.voiceforge.app.util

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.os.Build
import android.os.Environment
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.voiceforge.app.ui.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Copies a storage file into the public Downloads collection:
 *  - API ≥ 29: MediaStore.Downloads insert + stream copy (no permission);
 *  - API 26–28: WRITE_EXTERNAL_STORAGE runtime request, then a plain copy.
 *
 * [rememberExporter] returns `export(file, suggestedName)` and reports the
 * final file name through [onResult] (for the Snackbar) or [onError].
 */
object Export {

    /** Runs the copy off the main thread. @return the name actually written. */
    suspend fun toDownloads(context: Context, src: File, name: String): String =
        withContext(Dispatchers.IO) {
            require(src.exists()) { "missing source file" }
            val clean = sanitize(name)
            if (Build.VERSION.SDK_INT >= 29) {
                copyViaMediaStore(context, src, clean)
            } else {
                copyToDownloadsDir(src, clean)
            }
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun copyViaMediaStore(context: Context, src: File, name: String): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IOException("Could not open output stream")
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return queryDisplayName(resolver, uri) ?: name
    }

    @Suppress("DEPRECATION")   // external storage access before Q
    private fun copyToDownloadsDir(src: File, name: String): String {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Downloads unavailable")
        var dest = File(dir, name)
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var n = 1
        while (dest.exists()) {
            n++
            dest = File(dir, if (ext.isEmpty()) "$stem ($n)" else "$stem ($n).$ext")
        }
        src.copyTo(dest, overwrite = false)
        return dest.name
    }

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: android.net.Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        }.getOrNull()

    fun sanitize(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "voiceforge.mp3" }

    fun mimeType(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "m4a", "mp4" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}

/**
 * UI binding for [Export]: handles the API 26–28 permission dance, then runs
 * the copy and reports success/failure through the callbacks.
 */
@Composable
fun rememberExporter(
    onResult: (String) -> Unit,
    onError: (String) -> Unit,
): (File, String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<File, String>?>(null) }

    fun run(file: File, name: String) {
        scope.launch {
            val result = runCatching { Export.toDownloads(context, file, name) }
            if (result.isSuccess) {
                result.getOrNull()?.let(onResult)
            } else {
                onError(Strings.t("export_failed"))
            }
        }
    }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val job = pending
        pending = null
        if (granted && job != null) run(job.first, job.second)
        else onError(Strings.t("storage_rationale"))
    }

    return { file, name ->
        val needsPermission = Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pending = file to name
            launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            run(file, name)
        }
    }
}
