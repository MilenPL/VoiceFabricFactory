package com.voiceforge.app.ui.screens

import android.content.Context
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
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voiceforge.app.data.SavedItem
import com.voiceforge.app.data.Storage
import com.voiceforge.app.ui.LocalAppSnackbar
import com.voiceforge.app.ui.Strings
import com.voiceforge.app.util.TimeFmt
import com.voiceforge.app.util.rememberExporter
import com.voiceforge.app.viewmodel.EngineViewModel
import kotlinx.coroutines.launch

private val SORT_KEYS = listOf("date_desc", "date_asc", "name")
private val SORT_LABELS = listOf("sort_newest", "sort_oldest", "sort_name")

/**
 * Tab 4 — library of generated files: search, sort, play, download,
 * regenerate (with the stored settings) and delete.
 */
@Composable
fun SavedScreen(vm: EngineViewModel) {
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

    val prefs = remember {
        context.getSharedPreferences("voiceforge_ui", Context.MODE_PRIVATE)
    }
    var query by rememberSaveable { mutableStateOf("") }
    var sortKey by rememberSaveable {
        mutableStateOf(prefs.getString("sort", "date_desc") ?: "date_desc")
    }
    var sortMenu by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SavedItem?>(null) }

    var items by remember { mutableStateOf<List<SavedItem>>(emptyList()) }
    LaunchedEffect(state.libraryVersion, query, sortKey) {
        items = Storage.listSaved(sort = sortKey, query = query)
    }

    val player = vm.player
    val current by player.currentFile.collectAsState()
    val playing by player.isPlaying.collectAsState()

    val exporter = rememberExporter(
        onResult = { name -> msg(Strings.t("exported_to", mapOf("name" to name))) },
        onError = { msg(it) },
    )

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(Strings.t("search_saved")) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { sortMenu = true }) {
                    Icon(
                        Icons.AutoMirrored.Filled.Sort,
                        contentDescription = Strings.t(labelFor(sortKey)),
                    )
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    SORT_KEYS.forEachIndexed { i, key ->
                        DropdownMenuItem(
                            text = { Text(Strings.t(SORT_LABELS[i])) },
                            onClick = {
                                sortKey = key
                                prefs.edit().putString("sort", key).apply()
                                sortMenu = false
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (query.isBlank()) {
                    EmptyState(Icons.Filled.Save, Strings.t("no_saved"), Strings.t("no_saved_desc"))
                } else {
                    Text(
                        Strings.t("nothing_found"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    SavedRow(
                        item = item,
                        isCurrent = current == Storage.savedFile(item),
                        isPlaying = playing,
                        onPlay = {
                            val f = Storage.savedFile(item)
                            if (current == f) player.toggle(f) else player.play(f)
                        },
                        onDownload = { exporter(Storage.savedFile(item), "${item.name}.mp3") },
                        onRegenerate = {
                            if (state.isGenerating) return@SavedRow
                            vm.regenerate(item) { result ->
                                result.fold(
                                    onSuccess = { msg(Strings.t("done")) },
                                    onFailure = { e ->
                                        msg(e.message ?: Strings.t("failed"))
                                    },
                                )
                            }
                        },
                        onDelete = { deleteTarget = item },
                    )
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(Strings.t("delete_file_title", mapOf("name" to target.name))) },
            text = { Text(Strings.t("delete_file_msg")) },
            confirmButton = {
                TextButton(onClick = {
                    Storage.deleteSaved(target.id)
                    if (current == Storage.savedFile(target)) player.stop()
                    vm.notifyLibraryChanged()
                    msg(Strings.t("file_deleted"))
                    deleteTarget = null
                }) { Text(Strings.t("delete")) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(Strings.t("cancel")) }
            },
        )
    }
}

private fun labelFor(key: String): String {
    val i = SORT_KEYS.indexOf(key)
    return if (i >= 0) SORT_LABELS[i] else SORT_LABELS[0]
}

@Composable
private fun SavedRow(
    item: SavedItem,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
) {
    val file = Storage.savedFile(item)
    val duration = if (item.duration > 0) item.duration else TimeFmt.probeDuration(file)

    var subtitle = Strings.t("saved_on", mapOf("date" to TimeFmt.dateTime(item.created))) +
        " · ${TimeFmt.duration(duration)}"
    if (item.voiceName.isNotBlank()) subtitle += " · ${Strings.t("voice_lower")}: ${item.voiceName}"
    if (item.mood.isNotBlank()) subtitle += " · ${item.mood}"

    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
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
            IconButton(onClick = onPlay, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = Strings.t("play"),
                )
            }
            IconButton(onClick = onDownload, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Download, contentDescription = Strings.t("download"))
            }
            IconButton(onClick = onRegenerate, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = Strings.t("regenerate"))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = Strings.t("delete"))
            }
        }
    }
}
