package org.ryubing.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.ryubing.android.data.ControllerMappingRepository
import org.ryubing.android.data.DriverRepository
import org.ryubing.android.data.GamepadHotkeyRepository
import org.ryubing.android.data.GameEntry
import org.ryubing.android.data.GameRepository
import org.ryubing.android.data.SettingsRepository
import org.ryubing.android.emu.EmulationSession

/** Top-level in-app navigation. Kept dependency-light (no nav library needed yet). */
sealed interface Screen {
    data object Library : Screen
    data object Settings : Screen
    data class Emulation(val game: GameEntry) : Screen
}

@Composable
fun RyubingApp(
    gameRepository: GameRepository,
    settingsRepository: SettingsRepository,
    mappingRepository: ControllerMappingRepository,
    hotkeyRepository: GamepadHotkeyRepository,
    driverRepository: DriverRepository,
    session: EmulationSession,
    appDataPath: String,
    onControllerMappingChanged: () -> Unit,
    onHotkeysChanged: () -> Unit,
) {
    var screen: Screen by remember { mutableStateOf(Screen.Library) }

    when (val current = screen) {
        is Screen.Library -> GameLibraryScreen(
            repository = gameRepository,
            settingsRepository = settingsRepository,
            session = session,
            appDataPath = appDataPath,
            onOpenSettings = { screen = Screen.Settings },
            onPlay = { screen = Screen.Emulation(it) },
        )

        is Screen.Settings -> SettingsScreen(
            repository = settingsRepository,
            mappingRepository = mappingRepository,
            hotkeyRepository = hotkeyRepository,
            driverRepository = driverRepository,
            session = session,
            onMappingChanged = onControllerMappingChanged,
            onHotkeysChanged = onHotkeysChanged,
            onBack = { screen = Screen.Library },
        )

        is Screen.Emulation -> EmulationScreen(
            game = current.game,
            session = session,
            config = settingsRepository.load(),
            onExit = {
                // Navigate first — stop is async in EmulationScreen.dispose to avoid ANR.
                screen = Screen.Library
            },
        )
    }
}
