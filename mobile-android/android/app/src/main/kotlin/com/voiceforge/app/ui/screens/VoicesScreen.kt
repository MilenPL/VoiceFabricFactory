package com.voiceforge.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voiceforge.app.data.Storage
import com.voiceforge.app.data.VoiceSample
import com.voiceforge.app.ui.LocalAppSnackbar
import com.voiceforge.app.ui.Strings
import com.voiceforge.app.util.TimeFmt
import com.voiceforge.app.viewmodel.EngineViewModel
import kotlinx.coroutines.launch

/**
 * Tab 2 — manage voice samples: upload / record (shared with Compose),
 * preview, rename, delete.
 */
@Composable
fun VoicesScreen(vm: EngineViewModel) {
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHost = LocalAppSnackbar.current

    fun msg(s: String) {
        scope.launch {
            snackbarHost.currentSnackbarData?.dismiss()
            snackbarHost.showSnackbar(s)
        }
    }

    var voices by remember { mutableStateOf(Storage.listVoices()) }
    LaunchedEffect(state.voicesVersion) { voices = Storage.listVoices() }

    var renameTarget by remember { mutableStateOf<VoiceSample?>(null) }
    var deleteTarget by remember { mutableStateOf<VoiceSample?>(null) }

    val adder = rememberVoiceAdder(
        onAdded = { vm.notifyVoicesChanged() },
        onMessage = { msg(it) },
    )

    val player = vm.player
    val current by player.currentFile.collectAsState()
    val playing by player.isPlaying.collectAsState()
    val previewVoice = voices.find { Storage.voiceFile(it) == current }
    val mediaLine = when {
        previewVoice == null -> Strings.t("preview_idle")
        playing -> Strings.t("preview_playing", mapOf("name" to previewVoice.name))
        else -> Strings.t("preview_paused", mapOf("name" to previewVoice.name))
    }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(12.dp))
        VoiceToolbar(adder)
        Spacer(Modifier.height(6.dp))
        Text(
            mediaLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(6.dp))

        if (voices.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Filled.Mic, Strings.t("no_voices"), Strings.t("no_voices_desc"))
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(voices, key = { it.id }) { v ->
                    VoiceRow(
                        voice = v,
                        isCurrent = current == Storage.voiceFile(v),
                        isPlaying = playing,
                        onPreview = { player.toggle(Storage.voiceFile(v)) },
                        onRename = { renameTarget = v },
                        onDelete = { deleteTarget = v },
                    )
                }
            }
        }
    }

    renameTarget?.let { target ->
        var name by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(Strings.t("rename_voice")) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty()) {
                        Storage.renameVoice(target.id, trimmed)
                        vm.notifyVoicesChanged()
                        msg(Strings.t("voice_renamed"))
                    }
                    renameTarget = null
                }) { Text(Strings.t("ok")) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text(Strings.t("cancel")) }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(Strings.t("delete_voice_title", mapOf("name" to target.name))) },
            text = { Text(Strings.t("delete_voice_msg")) },
            confirmButton = {
                TextButton(onClick = {
                    val file = Storage.voiceFile(target)
                    Storage.deleteVoice(target.id)
                    if (current == file) player.stop()
                    vm.notifyVoicesChanged()
                    msg(Strings.t("voice_deleted"))
                    deleteTarget = null
                }) { Text(Strings.t("delete")) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(Strings.t("cancel")) }
            },
        )
    }

    adder.Dialogs()
}

@Composable
private fun VoiceRow(
    voice: VoiceSample,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onPreview: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val duration = if (voice.duration > 0) voice.duration else TimeFmt.probeDuration(Storage.voiceFile(voice))
    val sourceKey = if (voice.source == "record") "src_record" else "src_upload"
    val subtitle = "${TimeFmt.duration(duration)} · " +
        "${Strings.t("added_on", mapOf("date" to TimeFmt.dateTime(voice.created)))} · " +
        Strings.t(sourceKey)

    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    voice.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val paused = !isCurrent || !isPlaying
            IconButton(onClick = onPreview, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = Strings.t("preview"),
                )
            }
            IconButton(onClick = onRename, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Edit, contentDescription = Strings.t("rename"))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = Strings.t("delete"))
            }
        }
    }
}
