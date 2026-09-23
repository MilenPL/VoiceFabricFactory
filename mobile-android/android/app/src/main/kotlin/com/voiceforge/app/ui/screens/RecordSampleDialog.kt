package com.voiceforge.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.voiceforge.app.audio.Recorder
import com.voiceforge.app.data.Storage
import com.voiceforge.app.ui.Strings
import com.voiceforge.app.util.TimeFmt
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID

/**
 * Record a 16 kHz mono voice sample straight into a temp WAV:
 * mic permission → Record (tonal) / Stop (error) toggle → live timer with a
 * 10-minute auto-stop → Save once ≥ 6 s were captured. Save hands the file to
 * the caller (which then asks for a name); cancelling discards the temp file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordSampleDialog(
    onDismiss: () -> Unit,
    onSavedRecording: (File) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var denied by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<Recorder?>(null) }
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(0.0) }
    var stoppedSec by remember { mutableStateOf(0.0) }
    var handedOver by remember { mutableStateOf(false) }

    val tempFile = remember { File(Storage.stagingDir, "rec_${UUID.randomUUID().toString().replace("-", "").take(12)}.wav") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        granted = ok
        denied = !ok
    }

    // Ask on open when needed; a denial switches the sheet to the rationale.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Ticker: refreshes the timer and detects the 10-minute auto-stop.
    androidx.compose.runtime.LaunchedEffect(recording) {
        val r = recorder
        if (recording && r != null) {
            while (recording) {
                elapsed = r.elapsed
                if (!r.isRecording) {          // auto-stopped at MAX_SECONDS
                    stoppedSec = r.elapsed
                    recording = false
                    break
                }
                delay(250)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val r = recorder
            if (r != null && r.isRecording) r.stop()
            if (!handedOver) tempFile.delete()
        }
    }

    val startStop: () -> Unit = {
        if (recording) {
            val r = recorder
            stoppedSec = r?.stop() ?: stoppedSec
            recording = false
        } else {
            val r = Recorder(tempFile)
            if (r.start()) {
                recorder = r
                recording = true
                elapsed = 0.0
                stoppedSec = 0.0
            } else {
                onMessage(Strings.t("record_failed"))
            }
        }
    }

    val shownSec = if (recording) elapsed else stoppedSec
    val canSave = !recording && stoppedSec >= Recorder.MIN_SECONDS && tempFile.length() > 44

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(Strings.t("record_title"), style = MaterialTheme.typography.titleMedium)
            Text(
                Strings.t("record_hint"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!granted) {
                Text(
                    if (denied) Strings.t("mic_denied") else Strings.t("mic_rationale"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = {
                    denied = false
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text(Strings.t("mic_grant")) }
            }

            Text(
                "${TimeFmt.clock(shownSec)} / ${TimeFmt.clock(Recorder.MAX_SECONDS.toDouble())}",
                style = MaterialTheme.typography.headlineSmall,
            )
            if (recording) {
                Text(
                    Strings.t("recording"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (stoppedSec > 0) {
                Text(
                    "${TimeFmt.clock(stoppedSec)} ${Strings.t("recorded")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (recording) {
                Button(
                    onClick = startStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("  ${Strings.t("stop")}")
                }
            } else {
                FilledTonalButton(
                    onClick = startStop,
                    enabled = granted,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("  ${Strings.t("record")}")
                }
            }

            if (!recording && stoppedSec >= 0.001 && stoppedSec < Recorder.MIN_SECONDS) {
                Text(
                    Strings.t("record_min"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onDismiss) { Text(Strings.t("cancel")) }
                Button(
                    onClick = {
                        handedOver = true
                        onSavedRecording(tempFile)
                    },
                    enabled = canSave,
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  ${Strings.t("save")}")
                }
            }
        }
    }
}
