package com.voiceforge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import com.voiceforge.app.data.DemoVoices
import com.voiceforge.app.data.Storage
import com.voiceforge.app.ui.ThemeState
import com.voiceforge.app.ui.VoiceForgeApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Storage.init(applicationContext)
        DemoVoices.installIfEmpty(applicationContext)
        setContent {
            // ThemeState.mode is snapshot state → switching the mode recomposes live.
            MaterialTheme(
                colorScheme = when (ThemeState.mode.value) {
                    "light" -> lightColorScheme()
                    "dark" -> darkColorScheme()
                    else -> if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
                },
            ) {
                Surface {
                    VoiceForgeApp()
                }
            }
        }
    }
}
