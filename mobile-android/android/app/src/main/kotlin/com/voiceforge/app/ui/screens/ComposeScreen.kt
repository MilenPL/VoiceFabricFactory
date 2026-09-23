package com.voiceforge.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.voiceforge.app.data.GenSettings
import com.voiceforge.app.data.Storage
import com.voiceforge.app.ui.LocalAppSnackbar
import com.voiceforge.app.ui.Strings
import com.voiceforge.app.ui.ThemeState
import com.voiceforge.app.util.TimeFmt
import com.voiceforge.app.util.rememberExporter
import com.voiceforge.app.viewmodel.EngineViewModel
import kotlinx.coroutines.launch

/**
 * Tab 1 — voice + language, text, mood & delivery, generate, result player.
 * All delivery controls live in the ViewModel so the batch queue can reuse
 * exactly this selection.
 */
@Composable
fun ComposeScreen(vm: EngineViewModel) {
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
    val selectedVoice = voices.find { it.id == state.selectedVoiceId } ?: voices.firstOrNull()
    var text by rememberSaveable { mutableStateOf("") }

    val adder = rememberVoiceAdder(
        onAdded = { vm.notifyVoicesChanged() },
        onMessage = { msg(it) },
    )
    val exporter = rememberExporter(
        onResult = { name -> msg(Strings.t("exported_to", mapOf("name" to name))) },
        onError = { msg(it) },
    )

    fun generate() {
        val voice = selectedVoice
        if (voice == null) {
            msg(Strings.t("need_voice"))
            return
        }
        if (text.isBlank()) {
            msg(Strings.t("enter_text"))
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
        vm.generate(text.trim(), settings) { result ->
            result.fold(
                onSuccess = { msg(Strings.t("done")) },
                onFailure = { e -> msg("${Strings.t("failed")}: ${e.message ?: ""}") },
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ------------------------------------------------------------ voice
        SectionCard(Strings.t("voice")) {
            DropdownField(
                label = Strings.t("voice"),
                options = if (voices.isEmpty()) listOf(Strings.t("no_voices")) else voices.map { it.name },
                selectedIndex = selectedVoice?.let { voices.indexOf(it) } ?: 0,
                onSelect = { i -> voices.getOrNull(i)?.let { vm.setVoice(it.id) } },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { adder.upload() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  ${Strings.t("upload_sample")}")
                }
                OutlinedButton(onClick = { adder.record() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  ${Strings.t("record_sample")}")
                }
            }
            DropdownField(
                label = Strings.t("language"),
                options = LANGUAGES.map { it.first },
                selectedIndex = LANGUAGES.indexOfFirst { it.second == state.language }.coerceAtLeast(0),
                onSelect = { i -> LANGUAGES.getOrNull(i)?.let { vm.setLanguage(it.second) } },
            )
            DropdownField(
                label = Strings.t("ui_language"),
                options = listOf("EN", "PL"),
                selectedIndex = if (Strings.lang == "pl") 1 else 0,
                onSelect = { i -> vm.setUiLanguage(if (i == 1) "pl" else "en") },
            )
            DropdownField(
                label = Strings.t("theme"),
                options = listOf(
                    Strings.t("theme_system"),
                    Strings.t("theme_light"),
                    Strings.t("theme_dark"),
                ),
                selectedIndex = when (ThemeState.mode.value) {
                    "light" -> 1
                    "dark" -> 2
                    else -> 0
                },
                onSelect = { i ->
                    val m = when (i) {
                        1 -> "light"
                        2 -> "dark"
                        else -> "system"
                    }
                    ThemeState.mode.value = m
                    ThemeState.persist(m)
                },
            )
        }

        // -------------------------------------------------------------- text
        SectionCard(Strings.t("text")) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 5,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // --------------------------------------------------- mood & delivery
        SectionCard(Strings.t("mood_delivery")) {
            DropdownField(
                label = Strings.t("mood"),
                options = MOOD_PRESETS.map { it.name },
                selectedIndex = MOOD_PRESETS.indexOfFirst { it.name == state.mood }.coerceAtLeast(0),
                onSelect = { i ->
                    val preset = MOOD_PRESETS.getOrNull(i) ?: return@DropdownField
                    vm.setMood(preset.name)
                    vm.setDelivery(preset.pitch, preset.speed, preset.gain)
                },
            )
            LabeledSlider(
                label = Strings.t("loudness"),
                value = state.gain,
                onValueChange = vm::setGain,
                valueRange = -12f..12f,
                steps = 47,
                valueText = formatGain(state.gain),
            )
            LabeledSlider(
                label = Strings.t("speed"),
                value = state.speed,
                onValueChange = vm::setSpeed,
                valueRange = 0.5f..2f,
                steps = 29,
                valueText = formatSpeed(state.speed),
            )
            LabeledSlider(
                label = Strings.t("pitch"),
                value = state.pitch,
                onValueChange = vm::setPitch,
                valueRange = -6f..6f,
                steps = 23,
                valueText = formatPitch(state.pitch),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.normalize, onCheckedChange = vm::setNormalize)
                Text(
                    Strings.t("normalize"),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ---------------------------------------------------------- generate
        Button(
            onClick = { generate() },
            enabled = !state.isGenerating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("  ${Strings.t("generate")}")
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.isGenerating) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (state.status.isNotEmpty()) {
                Text(
                    Strings.t(state.status),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ------------------------------------------------------------ result
        SectionCard(Strings.t("result")) {
            val result = state.lastResult
            if (result == null) {
                EmptyState(
                    Icons.Filled.PlayArrow,
                    Strings.t("nothing_generated"),
                    Strings.t("nothing_generated_desc"),
                )
            } else {
                val player = vm.player
                val current by player.currentFile.collectAsState()
                val playing by player.isPlaying.collectAsState()
                val posMs by player.positionMs.collectAsState()
                val durMs by player.durationMs.collectAsState()

                val isCurrent = current == result.file
                val probedTotal = remember(result.file) { TimeFmt.probeDuration(result.file) }
                val totalMs = when {
                    isCurrent && durMs > 0 -> durMs
                    probedTotal > 0 -> (probedTotal * 1000).toLong()
                    else -> 0L
                }
                val progress = if (isCurrent && totalMs > 0) posMs.toFloat() / totalMs else 0f

                WaveformBar(
                    file = result.file,
                    progress = progress,
                    totalSeconds = if (totalMs > 0) totalMs / 1000.0 else 0.0,
                )

                var dragging by remember { mutableStateOf(false) }
                var dragFraction by remember { mutableStateOf(0f) }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledIconButton(
                        onClick = {
                            if (isCurrent) player.toggle(result.file) else player.play(result.file)
                        },
                    ) {
                        val paused = !isCurrent || !playing
                        Icon(
                            if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = if (paused) Strings.t("play") else Strings.t("pause"),
                        )
                    }
                    Text(
                        TimeFmt.position(if (isCurrent) posMs else 0L, totalMs),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Slider(
                        value = if (dragging) dragFraction else progress.coerceIn(0f, 1f),
                        onValueChange = {
                            dragging = true
                            dragFraction = it
                        },
                        onValueChangeFinished = {
                            val target = (dragFraction * totalMs).toLong()
                            if (isCurrent) player.seekTo(target) else player.play(result.file, target)
                            dragging = false
                        },
                        enabled = totalMs > 0,
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                vm.saveResult(result).fold(
                                    onSuccess = {
                                        msg(Strings.t("saved_as", mapOf("name" to it.name)))
                                    },
                                    onFailure = { msg(Strings.t("save_failed")) },
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  ${Strings.t("save_library")}")
                    }
                    OutlinedButton(
                        onClick = {
                            exporter(result.file, "${vm.suggestName(result.text)}.mp3")
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  ${Strings.t("export")}")
                    }
                }
                Text(
                    Strings.t("save_hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    adder.Dialogs()
}
