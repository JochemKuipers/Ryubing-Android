package org.ryubing.android

import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.ryubing.android.data.ControllerMappingRepository
import org.ryubing.android.data.DataFolderResolver
import org.ryubing.android.data.DriverRepository
import org.ryubing.android.data.GamepadHotkeyRepository
import org.ryubing.android.data.GameRepository
import org.ryubing.android.data.SettingsRepository
import org.ryubing.android.emu.EmulationSession
import org.ryubing.android.input.ControllerKeyCapture
import org.ryubing.android.input.DpadInputMode
import org.ryubing.android.input.PhysicalGamepadController
import org.ryubing.android.ui.RyubingApp
import org.ryubing.android.ui.theme.RyubingTheme

class MainActivity : ComponentActivity() {

    private lateinit var session: EmulationSession
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var mappingRepository: ControllerMappingRepository
    private lateinit var hotkeyRepository: GamepadHotkeyRepository
    private var physicalGamepad: PhysicalGamepadController? = null
    private var dpadInputMode: DpadInputMode = DpadInputMode.HatAxes

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val gameRepo = GameRepository(applicationContext)
        settingsRepository = SettingsRepository(applicationContext)
        mappingRepository = ControllerMappingRepository(applicationContext)
        hotkeyRepository = GamepadHotkeyRepository(applicationContext)
        val driverRepo = DriverRepository(applicationContext)
        // Emulator data (keys, saves, mods) follows the user-selected data folder; the core
        // receives this path via ryubing_initialize() → AppDataManager (custom mode).
        val dataDir = DataFolderResolver.resolve(applicationContext, settingsRepository.load())
        driverRepo.emulatorDataDir = dataDir
        session = EmulationSession(
            applicationContext,
            dataDir.absolutePath,
            contentResolver,
            driverRepo,
        )
        refreshPhysicalGamepad()

        setContent {
            RyubingTheme {
                RyubingApp(
                    gameRepository = gameRepo,
                    settingsRepository = settingsRepository,
                    mappingRepository = mappingRepository,
                    hotkeyRepository = hotkeyRepository,
                    driverRepository = driverRepo,
                    session = session,
                    appDataPath = dataDir.absolutePath,
                    onControllerMappingChanged = ::refreshPhysicalGamepad,
                    onHotkeysChanged = ::refreshPhysicalGamepad,
                )
            }
        }
    }

    fun refreshPhysicalGamepad() {
        val mapping = mappingRepository.load()
        val hotkeys = hotkeyRepository.load()
        dpadInputMode = mapping.dpadInputMode
        val existing = physicalGamepad
        if (existing != null) {
            existing.updateMapping(mapping)
            existing.updateHotkeys(hotkeys)
        } else {
            physicalGamepad = PhysicalGamepadController(session, mapping, hotkeys)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (ControllerKeyCapture.tryConsume(event)) return true
        if (physicalGamepad?.onKeyEvent(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event != null) {
            physicalGamepad?.onMotionEvent(event)
            val sources = event.source
            val isPadMotion =
                sources and (InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_GAMEPAD) != 0
            // Hat-axes mode: consume motion so Android does not also synthesize
            // FLAG_FALLBACK DPAD keys. Legacy mode (and remap capture) leave motion
            // unconsumed so KEYCODE_DPAD_* can reach the configurator / gameplay.
            if (isPadMotion &&
                dpadInputMode == DpadInputMode.HatAxes &&
                !ControllerKeyCapture.isActive
            ) {
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }
}
