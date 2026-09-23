package com.voiceforge.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voiceforge.app.data.GenSettings
import com.voiceforge.app.data.QueueItem
import com.voiceforge.app.data.QueueStatus
import com.voiceforge.app.data.SavedItem
import com.voiceforge.app.data.Storage
import com.voiceforge.app.data.VoiceSample
import com.voiceforge.app.ui.LocalAppSnackbar
import com.voiceforge.app.ui.Strings
import com.voiceforge.app.util.TimeFmt
import com.voiceforge.app.viewmodel.BatchListener
import com.voiceforge.app.viewmodel.EngineViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Tab 3 — batch queue: collect texts (typed or imported), then generate them
 * one after another with the current Compose-tab voice/mood/settings. Every
 * finished item is stored in the Saved library automatically.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BatchQueueScreen(vm: EngineViewModel) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = LocalAppSnackbar.current

    fun msg(s: String) {
        scope.launch {
            snackbarHost.currentSnackbarData?.dismiss()
            snackbarHost.showSnackbar(s)
        }
    }

    var items by remember { mutableStateOf<List<QueueItem>>(emptyList()) }
    var draft by rememberSaveable { mutableStateOf("") }

    fun updateItem(id: String, transform: (QueueItem) -> QueueItem) {
        items = items.map { if (it.id == id) transform(it) else it }
    }

    val listener = remember {
        object : BatchListener {
            override fun onStart(itemId: String) =
                updateItem(itemId) { it.copy(status = QueueStatus.RUNNING) }

            override fun onDone(itemId: String, saved: SavedItem) =
                updateItem(itemId) {
                    it.copy(status = QueueStatus.DONE, savedFile = saved.file, savedAt = saved.created)
                }

            override fun onError(itemId: String, error: String) =
                updateItem(itemId) { it.copy(status = QueueStatus.ERROR, error = error) }

            override fun onFinished() = msg(Strings.t("batch_finished"))
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.useLines { lines -> lines.map { it.trim() }.filter { it.isNotEmpty() }.toList() }
                        ?: error("unreadable")
                }
            }
            read.fold(
                onSuccess = { lines ->
                    if (lines.isEmpty()) {
                        msg(Strings.t("read_failed"))
                    } else {
                        items = items + lines.map { QueueItem(id = newId(), text = it) }
                        msg(addedMessage(lines.size))
                    }
                },
                onFailure = { msg(Strings.t("read_failed")) },
            )
        }
    }

    val voices = remember(state.voicesVersion) { Storage.listVoices() }
    val selectedVoice: VoiceSample? =
        voices.find { it.id == state.selectedVoiceId } ?: voices.firstOrNull()

    fun generateAll() {
        if (state.isGenerating) return
        val pending = items.filter { it.status == QueueStatus.PENDING }
        if (pending.isEmpty()) {
            msg(Strings.t("nothing_pending"))
            return
        }
        val voice = selectedVoice
        if (voice == null) {
            msg(Strings.t("need_voice"))
            return
        }
        val settings = GenSettings(
            mood = state.mood,
            pitch = state.pitch,
            speed = state.speed,
            gain = state.gain,
            normalize = state.normalize,
            language = state.language,
            voiceId = voice.id,
            voiceName = voice.name,
        )
        msg(Strings.t("queued"))
        vm.runBatch(pending, settings, listener)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            Strings.t("queue_info"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(Strings.t("new_entry")) },
            minLines = 2,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = {
                val t = draft.trim()
                if (t.isEmpty()) {
                    msg(Strings.t("type_first"))
                } else {
                    items = items + QueueItem(id = newId(), text = t)
                    draft = ""
                }
            }) { Text(Strings.t("add_queue")) }

            Button(
                onClick = { generateAll() },
                enabled = !state.isGenerating,
            ) { Text(Strings.t("generate_all")) }

            OutlinedButton(onClick = {
                importLauncher.launch(arrayOf("text/plain", "*/*"))
            }) { Text(Strings.t("import_txt")) }

            OutlinedButton(onClick = {
                items = items.filter {
                    it.status != QueueStatus.DONE && it.status != QueueStatus.ERROR
                }
            }) { Text(Strings.t("clear_finished")) }
        }

        if (state.isBatchRunning) {
            Spacer(Modifier.height(6.dp))
            val n = Strings.t("batch_of", mapOf("n" to state.batchIndex.toString(), "total" to state.batchTotal.toString()))
            Text(
                "$n — ${Strings.t(state.status)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(8.dp))

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    Icons.Filled.PlayArrow,
                    Strings.t("queue_empty"),
                    Strings.t("queue_empty_desc"),
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    QueueRow(
                        item = item,
                        onPlay = { file ->
                            val f = file
                            if (vm.player.currentFile.value == f) vm.player.toggle(f)
                            else vm.player.play(f)
                        },
                        onRemove = {
                            if (item.status == QueueStatus.RUNNING) {
                                msg(Strings.t("item_running"))
                            } else {
                                items = items.filterNot { it.id == item.id }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    item: QueueItem,
    onPlay: (java.io.File) -> Unit,
    onRemove: () -> Unit,
) {
    val shown = if (item.text.length > 90) item.text.take(90) + "…" else item.text
    val statusText = when (item.status) {
        QueueStatus.PENDING -> Strings.t("status_waiting")
        QueueStatus.RUNNING -> Strings.t("status_running")
        QueueStatus.DONE -> item.savedAt?.let {
            Strings.t("status_saved", mapOf("date" to TimeFmt.dateTime(it)))
        } ?: Strings.t("status_saved_now")

        QueueStatus.ERROR -> Strings.t(
            "status_error",
            mapOf("error" to (item.error ?: Strings.t("failed"))),
        )
    }

    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    shown,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (item.status) {
                        QueueStatus.ERROR -> MaterialTheme.colorScheme.error
                        QueueStatus.DONE -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.status == QueueStatus.DONE && item.savedFile != null) {
                val file = java.io.File(Storage.savedDir, item.savedFile)
                IconButton(onClick = { onPlay(file) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = Strings.t("play_result"))
                }
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Close, contentDescription = Strings.t("remove_entry"))
            }
        }
    }
}

private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(8)

/** Polish has three plural forms for "entry" — pick the right one. */
private fun addedMessage(n: Int): String = when {
    n == 1 -> Strings.t("queue_added_1")
    n % 10 in 2..4 && n % 100 !in 12..14 ->
        Strings.t("queue_added_few", mapOf("n" to n.toString()))

    else -> Strings.t("queue_added_many", mapOf("n" to n.toString()))
}
