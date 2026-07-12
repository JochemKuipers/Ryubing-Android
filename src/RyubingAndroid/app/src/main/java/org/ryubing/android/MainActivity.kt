package org.ryubing.android

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.ryubing.android.data.ControllerMappingRepository
import org.ryubing.android.data.DriverRepository
import org.ryubing.android.data.GameRepository
import org.ryubing.android.data.SettingsRepository
import org.ryubing.android.emu.EmulationSession
import org.ryubing.android.input.ControllerKeyCapture
import org.ryubing.android.input.PhysicalGamepadController
import org.ryubing.android.ui.RyubingApp
import org.ryubing.android.ui.theme.RyubingTheme

class MainActivity : ComponentActivity() {

    private lateinit var session: EmulationSession
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var mappingRepository: ControllerMappingRepository
    private var physicalGamepad: PhysicalGamepadController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val gameRepo = GameRepository(applicationContext)
        settingsRepository = SettingsRepository(applicationContext)
        mappingRepository = ControllerMappingRepository(applicationContext)
        val driverRepo = DriverRepository(applicationContext)
        session = EmulationSession(
            applicationContext,
            filesDir.absolutePath,
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
                    driverRepository = driverRepo,
                    session = session,
                    onControllerMappingChanged = ::refreshPhysicalGamepad,
                )
            }
        }
    }

    fun refreshPhysicalGamepad() {
        val mapping = mappingRepository.load()
        val existing = physicalGamepad
        if (existing != null) {
            existing.updateMapping(mapping)
        } else {
            physicalGamepad = PhysicalGamepadController(session, mapping)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (ControllerKeyCapture.tryConsume(event)) return true
        if (physicalGamepad?.onKeyEvent(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent?): Boolean {
        event?.let { physicalGamepad?.onMotionEvent(it) }
        return super.dispatchGenericMotionEvent(event)
    }
}
