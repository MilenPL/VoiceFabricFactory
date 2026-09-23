package com.voiceforge.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voiceforge.app.data.Storage
import com.voiceforge.app.data.VoiceSample
import com.voiceforge.app.ui.Strings
import com.voiceforge.app.util.TimeFmt
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Shared voice-entry flow for the Compose and Voices tabs: pick an audio file
 * (ACTION_OPEN_DOCUMENT, MIME type audio), verify it is ≥ 6 s, then name it
 * and store it in voices/ as "<uuid12>.<ext>". Recording opens the record
 * dialog, which hands a finished WAV back through the same naming path.
 */
@Composable
fun rememberVoiceAdder(
    onAdded: (VoiceSample) -> Unit,
    onMessage: (String) -> Unit,
): VoiceAdder {
    val context = LocalContext.current

    var pendingFile by remember { mutableStateOf<File?>(null) }
    var pendingExt by remember { mutableStateOf("wav") }
    var pendingName by remember { mutableStateOf("") }
    var nameVisible by remember { mutableStateOf(false) }
    var recordVisible by remember { mutableStateOf(false) }
    var tooShort by remember { mutableStateOf<Double?>(null) }

    val confirmName: (String) -> Unit = { raw ->
        val src = pendingFile
        if (src == null) {
            nameVisible = false
        } else {
            val name = raw.trim().ifBlank { Strings.t("untitled_voice") }
            val id = UUID.randomUUID().toString().replace("-", "").take(12)
            val dest = File(Storage.voicesDir, "$id.$pendingExt")
            try {
                if (src.absolutePath != dest.absolutePath) src.copyTo(dest, overwrite = true)
                val sample = VoiceSample(
                    id = id,
                    name = name,
                    file = dest.name,
                    created = System.currentTimeMillis(),
                    duration = TimeFmt.probeDuration(dest),
                    source = if (pendingExt == "wav" && src.name.startsWith("rec_")) "record" else "upload",
                )
                Storage.addVoice(sample)
                if (src.absolutePath != dest.absolutePath) src.delete()
                onAdded(sample)
                onMessage(Strings.t("voice_added", mapOf("name" to name)))
            } catch (e: Exception) {
                runCatching { if (dest.exists()) dest.delete() }
                runCatching { src.delete() }
                onMessage("${Strings.t("import_failed")}${e.message?.let { ": $it" } ?: ""}")
            }
            pendingFile = null
            nameVisible = false
        }
    }

    val dismissName: () -> Unit = {
        pendingFile?.delete()
        pendingFile = null
        nameVisible = false
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val display = queryDisplayName(context, uri)
            val ext = display.substringAfterLast('.', "").lowercase()
                .ifBlank { "bin" }
            val tmp = File(Storage.stagingDir, "upload_${token()}.${ext}")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("empty stream")

            val dur = TimeFmt.probeDuration(tmp)
            if (dur >= 0.01 && dur < 6.0) {
                tmp.delete()
                tooShort = dur
            } else {
                pendingFile = tmp
                pendingExt = ext
                pendingName = display.substringBeforeLast('.').ifBlank { Strings.t("untitled_voice") }
                nameVisible = true
            }
        } catch (e: Exception) {
            onMessage("${Strings.t("upload_failed")}${e.message?.let { ": $it" } ?: ""}")
        }
    }

    return VoiceAdder(
        pickAudio = { launcher.launch(arrayOf("audio/*")) },
        showRecordDialog = { recordVisible = true },
        tooShort = tooShort,
        onTooShortDismiss = { tooShort = null },
        nameVisible = nameVisible,
        initialName = pendingName,
        onConfirmName = confirmName,
        onDismissName = dismissName,
        recordVisible = recordVisible,
        onRecordDismiss = { recordVisible = false },
        onRecordingSaved = { file ->
            pendingFile = file
            pendingExt = "wav"
            pendingName = Strings.t("default_voice_name")
            recordVisible = false
            nameVisible = true
        },
        onMessage = onMessage,
    )
}

/** Handle returned by [rememberVoiceAdder]; render [Dialogs] once per screen. */
class VoiceAdder internal constructor(
    private val pickAudio: () -> Unit,
    private val showRecordDialog: () -> Unit,
    private val tooShort: Double?,
    private val onTooShortDismiss: () -> Unit,
    private val nameVisible: Boolean,
    private val initialName: String,
    private val onConfirmName: (String) -> Unit,
    private val onDismissName: () -> Unit,
    private val recordVisible: Boolean,
    private val onRecordDismiss: () -> Unit,
    private val onRecordingSaved: (File) -> Unit,
    private val onMessage: (String) -> Unit,
) {
    fun upload() = pickAudio()
    fun record() = showRecordDialog()

    /** All dialogs owned by the flow — call once from the screen's root. */
    @Composable
    fun Dialogs() {
        tooShort?.let { sec ->
            AlertDialog(
                onDismissRequest = onTooShortDismiss,
                title = { Text(Strings.t("too_short_title")) },
                text = {
                    Text(
                        Strings.t(
                            "sample_too_short",
                            mapOf("sec" to String.format(Locale.ROOT, "%.1f", sec)),
                        ),
                    )
                },
                confirmButton = { TextButton(onClick = onTooShortDismiss) { Text(Strings.t("ok")) } },
            )
        }

        if (nameVisible) {
            NameDialog(initial = initialName, onConfirm = onConfirmName, onDismiss = onDismissName)
        }

        if (recordVisible) {
            RecordSampleDialog(
                onDismiss = onRecordDismiss,
                onSavedRecording = onRecordingSaved,
                onMessage = onMessage,
            )
        }
    }
}

@Composable
private fun NameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.t("name_voice")) },
        text = {
            Column {
                Text(Strings.t("name_voice_desc"), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(Strings.t("save")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(Strings.t("cancel")) }
        },
    )
}

/** Shared toolbar buttons (upload / record) used above the voices list. */
@Composable
fun VoiceToolbar(adder: VoiceAdder) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.OutlinedButton(
            onClick = { adder.upload() },
            modifier = Modifier.weight(1f),
        ) { Text(Strings.t("upload_voice")) }
        androidx.compose.material3.OutlinedButton(
            onClick = { adder.record() },
            modifier = Modifier.weight(1f),
        ) { Text(Strings.t("record_voice")) }
    }
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "sample"

private fun token(): String = UUID.randomUUID().toString().replace("-", "").take(12)
