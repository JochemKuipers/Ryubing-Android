package org.ryubing.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.ryubing.android.data.GameEntry
import org.ryubing.android.data.GameRepository
import org.ryubing.android.data.SettingsRepository
import org.ryubing.android.emu.EmulationSession

/** Top-level in-app navigation. Kept dependency-light (no nav library needed yet). */
sealed interface Screen {
    data object Library : Screen
    data object Settings : Screen
    data object Drivers : Screen
    data class Emulation(val game: GameEntry) : Screen
}

@Composable
fun RyubingApp(
    gameRepository: GameRepository,
    settingsRepository: SettingsRepository,
    session: EmulationSession,
) {
    var screen: Screen by remember { mutableStateOf(Screen.Library) }

    when (val current = screen) {
        is Screen.Library -> GameLibraryScreen(
            repository = gameRepository,
            onOpenSettings = { screen = Screen.Settings },
            onOpenDrivers = { screen = Screen.Drivers },
            onPlay = { screen = Screen.Emulation(it) },
        )

        is Screen.Settings -> SettingsScreen(
            repository = settingsRepository,
            onBack = { screen = Screen.Library },
        )

        is Screen.Drivers -> DriverManagerScreen(
            onBack = { screen = Screen.Library },
        )

        is Screen.Emulation -> EmulationScreen(
            game = current.game,
            session = session,
            config = settingsRepository.load(),
            onExit = {
                session.stop()
                screen = Screen.Library
            },
        )
    }
}
