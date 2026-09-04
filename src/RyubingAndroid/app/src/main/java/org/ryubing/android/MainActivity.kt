package org.ryubing.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.ryubing.android.data.ControllerMappingRepository
import org.ryubing.android.data.AppLifecycleStore
import org.ryubing.android.data.DataFolderResolver
import org.ryubing.android.data.DriverRepository
import org.ryubing.android.data.GamepadHotkeyRepository
import org.ryubing.android.data.GameEntry
import org.ryubing.android.data.GameRepository
import org.ryubing.android.data.SettingsRepository
import org.ryubing.android.emu.EmulationSession
import org.ryubing.android.input.ControllerKeyCapture
import org.ryubing.android.input.DpadInputMode
import org.ryubing.android.input.PhysicalGamepadController
import org.ryubing.android.ui.RyubingApp
import org.ryubing.android.ui.theme.RyubingTheme
import java.io.File

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
        val lifecycleStore = AppLifecycleStore(applicationContext)
        val systemDriverCrashed = lifecycleStore.consumeSystemDriverCrash()
        val initialGame = lifecycleStore.consumePendingLaunch()
            ?: resolveLaunchGame(gameRepo, intent)
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
            lifecycleStore,
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
                    lifecycleStore = lifecycleStore,
                    initialGame = initialGame,
                    systemDriverCrashed = systemDriverCrashed,
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

    companion object {
        private const val TAG = "MainActivity"

        /** adb: `am start -n … --es org.ryubing.android.LAUNCH_TITLE_ID 01008F6008C5E000` */
        const val EXTRA_LAUNCH_TITLE_ID = "org.ryubing.android.LAUNCH_TITLE_ID"

        /** adb: `am start -n … --es org.ryubing.android.LAUNCH_PATH /storage/.../game.nsp` */
        const val EXTRA_LAUNCH_PATH = "org.ryubing.android.LAUNCH_PATH"

        fun resolveLaunchGame(gameRepo: GameRepository, intent: Intent?): GameEntry? {
            if (intent == null) return null

            val path = intent.getStringExtra(EXTRA_LAUNCH_PATH)?.trim().orEmpty()
            if (path.isNotEmpty()) {
                val file = File(path)
                if (!file.isFile) {
                    Log.w(TAG, "LAUNCH_PATH missing or not a file: $path")
                    return null
                }
                return GameEntry(
                    title = file.nameWithoutExtension,
                    uri = Uri.fromFile(file),
                    sizeBytes = file.length(),
                    fileName = file.name,
                )
            }

            val titleId = intent.getStringExtra(EXTRA_LAUNCH_TITLE_ID)?.trim().orEmpty()
            if (titleId.isNotEmpty()) {
                val game = gameRepo.findByTitleId(titleId)
                if (game == null) {
                    Log.w(TAG, "LAUNCH_TITLE_ID not in library (add the dump once): $titleId")
                }
                return game
            }

            return null
        }
    }
}
