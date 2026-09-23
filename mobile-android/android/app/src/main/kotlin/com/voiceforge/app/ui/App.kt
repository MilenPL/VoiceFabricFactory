package com.voiceforge.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voiceforge.app.ui.screens.BatchQueueScreen
import com.voiceforge.app.ui.screens.ComposeScreen
import com.voiceforge.app.ui.screens.SavedScreen
import com.voiceforge.app.ui.screens.VoicesScreen
import com.voiceforge.app.viewmodel.EngineViewModel

/** Shared Snackbar host for every screen (all feedback goes through it). */
val LocalAppSnackbar = compositionLocalOf { SnackbarHostState() }

/** Top-level tabs. Labels go through the i18n table in Strings.kt. */
enum class Tab(val icon: ImageVector, val labelKey: String) {
    COMPOSE(Icons.Filled.GraphicEq, "tab_compose"),
    VOICES(Icons.Filled.Mic, "tab_voices"),
    QUEUE(Icons.AutoMirrored.Filled.QueueMusic, "tab_queue"),
    SAVED(Icons.Filled.Save, "tab_saved"),
}

@Composable
fun VoiceForgeApp(vm: EngineViewModel = viewModel()) {
    var tab by remember { mutableStateOf(Tab.COMPOSE) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Boot: restore config (UI language + normalize) and warm the engine up.
    LaunchedEffect(Unit) {
        vm.initFromConfig()
        vm.prepare()
    }

    CompositionLocalProvider(LocalAppSnackbar provides snackbarHostState) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(Strings.t(t.labelKey)) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    Tab.COMPOSE -> ComposeScreen(vm)
                    Tab.VOICES -> VoicesScreen(vm)
                    Tab.QUEUE -> BatchQueueScreen(vm)
                    Tab.SAVED -> SavedScreen(vm)
                }
            }
        }
    }
}
