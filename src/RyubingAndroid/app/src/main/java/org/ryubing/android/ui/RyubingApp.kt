package org.ryubing.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.ryubing.android.data.ControllerMappingRepository
import org.ryubing.android.ProcessRestarter
import org.ryubing.android.data.AppLifecycleStore
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
    lifecycleStore: AppLifecycleStore,
    initialGame: GameEntry?,
    systemDriverCrashed: Boolean,
    onControllerMappingChanged: () -> Unit,
    onHotkeysChanged: () -> Unit,
) {
    var screen: Screen by remember {
        mutableStateOf(initialGame?.let(Screen::Emulation) ?: Screen.Library)
    }
    var openDrivers by remember { mutableStateOf(false) }

    when (val current = screen) {
        is Screen.Library -> GameLibraryScreen(
            repository = gameRepository,
            settingsRepository = settingsRepository,
            session = session,
            appDataPath = appDataPath,
            systemDriverCrashed = systemDriverCrashed,
            onOpenSettings = { screen = Screen.Settings },
            onOpenDrivers = {
                openDrivers = true
                screen = Screen.Settings
            },
            onPlay = { game ->
                lifecycleStore.queueLaunch(game)
                ProcessRestarter.restart(gameRepository.context)
            },
        )

        is Screen.Settings -> SettingsScreen(
            repository = settingsRepository,
            mappingRepository = mappingRepository,
            hotkeyRepository = hotkeyRepository,
            driverRepository = driverRepository,
            session = session,
            initialDrivers = openDrivers,
            onMappingChanged = onControllerMappingChanged,
            onHotkeysChanged = onHotkeysChanged,
            onBack = {
                openDrivers = false
                screen = Screen.Library
            },
        )

        is Screen.Emulation -> EmulationScreen(
            game = current.game,
            session = session,
            config = settingsRepository.load(),
            onExit = {
                // Intentional restart: fresh process per session (clean VA space, driver state).
                ProcessRestarter.restart(gameRepository.context)
            },
        )
    }
}
