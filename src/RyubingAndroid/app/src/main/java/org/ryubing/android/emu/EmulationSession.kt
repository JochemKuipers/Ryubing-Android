package org.ryubing.android.emu

import android.util.Log
import android.view.Surface
import org.ryubing.android.RyubingNative
import org.ryubing.android.data.EmulatorConfig
import org.ryubing.android.input.SwitchButton
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-level glue over the [RyubingNative] C ABI. Owns the current button state and
 * forwards lifecycle + input to libryubing.so. One instance per running title.
 */
class EmulationSession(private val appDataPath: String) {

    private val buttonState = AtomicInteger(0)
    @Volatile private var initialized = false

    fun initialize() {
        if (initialized) return
        RyubingNative.ensureLoaded()
        val rc = RyubingNative.core.ryubing_initialize(appDataPath)
        check(rc == 1) { "ryubing_initialize failed" }
        initialized = true
    }

    fun applyConfig(config: EmulatorConfig) {
        RyubingNative.core.apply {
            ryubing_set_memory_config(config.memoryConfiguration, config.memoryManagerMode)
            ryubing_set_system_config(config.systemLanguage, config.systemRegion, config.dockedMode.toInt(), config.enablePptc.toInt())
            ryubing_set_graphics_config(config.resScale, config.enableShaderCache.toInt(), config.backendThreading)
        }
    }

    /** The SurfaceView hands us its Surface; forward it to the JNI shim. */
    fun setSurface(surface: Surface?) = RyubingNative.setSurface(surface)

    fun start(gamePath: String): Boolean {
        val ok = RyubingNative.core.ryubing_load_application(gamePath) == 1
        if (!ok) Log.e(TAG, "Failed to load $gamePath")
        return ok
    }

    val isRunning: Boolean get() = initialized && RyubingNative.core.ryubing_is_running() == 1

    // --- Input ---

    fun setButton(button: SwitchButton, pressed: Boolean) {
        val updated = if (pressed) buttonState.get() or button.bit else buttonState.get() and button.bit.inv()
        buttonState.set(updated)
        RyubingNative.core.ryubing_set_button_state(updated)
    }

    fun setStick(right: Boolean, x: Float, y: Float) =
        RyubingNative.core.ryubing_set_stick_state(if (right) 1 else 0, x, y)

    fun setMotion(ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float) =
        RyubingNative.core.ryubing_set_motion_state(ax, ay, az, gx, gy, gz)

    fun stop() {
        if (initialized) RyubingNative.core.ryubing_stop()
    }

    fun shutdown() {
        if (initialized) RyubingNative.core.ryubing_shutdown()
        initialized = false
    }

    private fun Boolean.toInt() = if (this) 1 else 0

    companion object {
        private const val TAG = "EmulationSession"
    }
}
