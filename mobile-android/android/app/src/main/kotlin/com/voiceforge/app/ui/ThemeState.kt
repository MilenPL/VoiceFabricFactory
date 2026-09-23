package com.voiceforge.app.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.voiceforge.app.data.Storage

/**
 * App-wide dark-theme selection: "system" | "light" | "dark".
 *
 * [mode] is a snapshot state read inside `setContent`, so flipping it
 * recomposes `MaterialTheme(colorScheme = …)` live. The value is initialized
 * from the persisted config (see [Storage.loadConfig]) and written back with
 * [persist] whenever the user picks a different mode.
 */
object ThemeState {
    /** Current mode; default "system" (follow the system dark-theme setting). */
    var mode: MutableState<String> = mutableStateOf(
        runCatching { Storage.loadConfig().theme }.getOrDefault("system"),
    )

    /** Update the mode and save it into config.json's "theme" key. */
    fun persist(mode: String) {
        this.mode.value = mode
        runCatching { Storage.saveTheme(mode) }
    }
}
