package org.ryubing.android

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.ryubing.android.data.EmulatorConfig
import org.ryubing.android.data.GameRepository
import org.ryubing.android.data.SettingsRepository
import org.ryubing.android.emu.EmulationSession
import org.ryubing.android.ui.RyubingApp
import org.ryubing.android.ui.theme.RyubingTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val gameRepo = GameRepository(applicationContext)
        val settingsRepo = SettingsRepository(applicationContext)
        val session = EmulationSession(filesDir.absolutePath, contentResolver)

        setContent {
            RyubingTheme {
                RyubingApp(
                    gameRepository = gameRepo,
                    settingsRepository = settingsRepo,
                    session = session,
                )
            }
        }
    }
}
