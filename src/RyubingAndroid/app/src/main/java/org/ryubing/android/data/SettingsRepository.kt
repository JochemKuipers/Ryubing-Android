package org.ryubing.android.data

import android.content.Context
import androidx.core.content.edit

/**
 * Persists [EmulatorConfig] via SharedPreferences. Simple by design; a richer store can
 * replace this later without touching the emulator glue.
 */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("ryubing_settings", Context.MODE_PRIVATE)

    fun load(): EmulatorConfig = EmulatorConfig(
        memoryConfiguration = prefs.getInt(KEY_MEM_CONFIG, 0),
        memoryManagerMode = prefs.getInt(KEY_MEM_MODE, 2),
        systemLanguage = prefs.getInt(KEY_LANGUAGE, 1),
        systemRegion = prefs.getInt(KEY_REGION, 1),
        dockedMode = prefs.getBoolean(KEY_DOCKED, false),
        enablePptc = prefs.getBoolean(KEY_PPTC, false),
        enableShaderCache = prefs.getBoolean(KEY_SHADER_CACHE, true),
        backendThreading = prefs.getInt(KEY_BACKEND_THREADING, 0),
        resScale = prefs.getFloat(KEY_RES_SCALE, 1f),
        useSwitchLayout = prefs.getBoolean(KEY_SWITCH_LAYOUT, true),
        showTouchControls = prefs.getBoolean(KEY_TOUCH_CONTROLS, true),
    )

    fun save(config: EmulatorConfig) = prefs.edit {
        putInt(KEY_MEM_CONFIG, config.memoryConfiguration)
        putInt(KEY_MEM_MODE, config.memoryManagerMode)
        putInt(KEY_LANGUAGE, config.systemLanguage)
        putInt(KEY_REGION, config.systemRegion)
        putBoolean(KEY_DOCKED, config.dockedMode)
        putBoolean(KEY_PPTC, config.enablePptc)
        putBoolean(KEY_SHADER_CACHE, config.enableShaderCache)
        putInt(KEY_BACKEND_THREADING, config.backendThreading)
        putFloat(KEY_RES_SCALE, config.resScale)
        putBoolean(KEY_SWITCH_LAYOUT, config.useSwitchLayout)
        putBoolean(KEY_TOUCH_CONTROLS, config.showTouchControls)
    }

    private companion object {
        const val KEY_MEM_CONFIG = "mem_config"
        const val KEY_MEM_MODE = "mem_mode"
        const val KEY_LANGUAGE = "language"
        const val KEY_REGION = "region"
        const val KEY_DOCKED = "docked"
        const val KEY_PPTC = "pptc"
        const val KEY_SHADER_CACHE = "shader_cache"
        const val KEY_BACKEND_THREADING = "backend_threading"
        const val KEY_RES_SCALE = "res_scale"
        const val KEY_SWITCH_LAYOUT = "switch_layout"
        const val KEY_TOUCH_CONTROLS = "touch_controls"
    }
}
