package com.voiceforge.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

/**
 * XTTS-v2 languages — exact list from the Linux app (Polish first, default).
 * Native names are intentional and identical in every UI language.
 */
val LANGUAGES: List<Pair<String, String>> = listOf(
    "Polski (Polish)" to "pl",
    "English" to "en",
    "Deutsch (German)" to "de",
    "Español (Spanish)" to "es",
    "Français (French)" to "fr",
    "Italiano" to "it",
    "Português" to "pt",
    "Русский" to "ru",
    "Türkçe" to "tr",
    "Nederlands" to "nl",
    "Čeština" to "cs",
    "Magyar" to "hu",
    "中文 (Chinese)" to "zh",
    "日本語 (Japanese)" to "ja",
    "한국어 (Korean)" to "ko",
    "हिन्दी (Hindi)" to "hi",
    "العربية (Arabic)" to "ar",
)

/** Mood presets (exact values — selecting one presets the three sliders). */
data class MoodPreset(val name: String, val pitch: Float, val speed: Float, val gain: Float)

val MOOD_PRESETS: List<MoodPreset> = listOf(
    MoodPreset("Neutral", 0.0f, 1.00f, 0.0f),
    MoodPreset("Happy", 1.0f, 1.08f, 1.5f),
    MoodPreset("Sad", -1.0f, 0.90f, -1.5f),
    MoodPreset("Angry", 0.5f, 1.12f, 2.5f),
    MoodPreset("Calm", -0.5f, 0.93f, -1.0f),
    MoodPreset("Excited", 1.5f, 1.16f, 2.0f),
    MoodPreset("Whisper", 0.0f, 0.95f, -5.0f),
    MoodPreset("Serious", -0.5f, 0.97f, 0.5f),
)

// ------------------------------------------------------------ slider values

/** "1.5 dB" / "-5 dB" — trailing zeros trimmed. */
fun formatGain(v: Float): String = num(v, minDecimals = 0) + " dB"

/** "1.0×" / "1.05×" — at least one decimal. */
fun formatSpeed(v: Float): String = num(v, minDecimals = 1) + "×"

/** "0 st" / "0.5 st". */
fun formatPitch(v: Float): String = num(v, minDecimals = 0) + " st"

private fun num(v: Float, minDecimals: Int): String {
    val r = (v * 100).roundToInt() / 100.0
    var s = String.format(Locale.ROOT, "%.2f", r)
        .dropLastWhile { it == '0' }
        .dropLastWhile { it == '.' }
    if (s.isEmpty() || s == "-") s = "0"
    if (s.substringAfter('.', "").length < minDecimals) {
        s = String.format(Locale.ROOT, "%.${minDecimals}f", r)
    }
    return s
}

// ------------------------------------------------------------------- pieces

/** Titled card used for every Compose-page frame. */
@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** Centered icon + title + description, used for all empty states. */
@Composable
fun EmptyState(icon: ImageVector, title: String, description: String) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 200.dp).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Read-only dropdown text field (M3 exposed dropdown). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownField(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = options.getOrElse(selectedIndex) { options.firstOrNull() ?: "" },
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEachIndexed { i, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelect(i)
                    },
                )
            }
        }
    }
}

/** Label + live value on one line, slider below (no fixed widths). */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueText: String,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
