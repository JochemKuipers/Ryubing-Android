package org.ryubing.android

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.ryubing.android.data.DriverRepository
import org.ryubing.android.data.GameRepository
import org.ryubing.android.data.SettingsRepository
import org.ryubing.android.emu.EmulationSession
import org.ryubing.android.input.PhysicalGamepadController
import org.ryubing.android.ui.RyubingApp
import org.ryubing.android.ui.theme.RyubingTheme

class MainActivity : ComponentActivity() {

    private lateinit var session: EmulationSession
    private lateinit var settingsRepository: SettingsRepository
    private var physicalGamepad: PhysicalGamepadController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val gameRepo = GameRepository(applicationContext)
        settingsRepository = SettingsRepository(applicationContext)
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
                    driverRepository = driverRepo,
                    session = session,
                )
            }
        }
    }

    fun refreshPhysicalGamepad() {
        physicalGamepad = PhysicalGamepadController(
            session = session,
            useSwitchLayout = settingsRepository.load().useSwitchLayout,
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (physicalGamepad?.onKeyEvent(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent?): Boolean {
        event?.let { physicalGamepad?.onMotionEvent(it) }
        return super.dispatchGenericMotionEvent(event)
    }
}
