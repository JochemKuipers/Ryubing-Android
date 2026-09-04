package org.ryubing.android.data

import android.content.Context
import androidx.core.content.edit

/**
 * Persists [EmulatorConfig] via SharedPreferences. Simple by design; a richer store can
 * replace this later without touching the emulator glue.
 */
class SettingsRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("ryubing_settings", Context.MODE_PRIVATE)

    fun load(): EmulatorConfig = EmulatorConfig(
        memoryConfiguration = prefs.getInt(KEY_MEM_CONFIG, defaultMemoryConfiguration()),
        memoryManagerMode = prefs.getInt(KEY_MEM_MODE, 2),
        useNce = prefs.getBoolean(KEY_USE_NCE, false),
        nceDebugLevel = prefs.getInt(KEY_NCE_DEBUG_LEVEL, 3).coerceIn(0, 3),
        systemLanguage = prefs.getInt(KEY_LANGUAGE, 1),
        systemRegion = prefs.getInt(KEY_REGION, 1),
        dockedMode = prefs.getBoolean(KEY_DOCKED, false),
        enablePptc = prefs.getBoolean(KEY_PPTC, false),
        enableLowPowerPtc = prefs.getBoolean(KEY_LOW_POWER_PTC, false),
        enableFsIntegrity = prefs.getBoolean(KEY_FS_INTEGRITY, true),
        enableInternet = prefs.getBoolean(KEY_INTERNET, false),
        ignoreMissingServices = prefs.getBoolean(KEY_IGNORE_MISSING, false),
        matchSystemTime = prefs.getBoolean(KEY_MATCH_SYSTEM_TIME, false),
        timeZone = prefs.getString(KEY_TIME_ZONE, "UTC") ?: "UTC",
        systemTimeOffset = prefs.getLong(KEY_SYSTEM_TIME_OFFSET, 0L),
        tickScalar = prefs.getLong(KEY_TICK_SCALAR, 200L),
        enableShaderCache = prefs.getBoolean(KEY_SHADER_CACHE, true),
        backendThreading = prefs.getInt(KEY_BACKEND_THREADING, 0),
        resScale = prefs.getFloat(KEY_RES_SCALE, 1f),
        vsyncMode = prefs.getInt(KEY_VSYNC_MODE, 0),
        customVSyncInterval = prefs.getInt(KEY_CUSTOM_VSYNC_INTERVAL, 120),
        enableCustomVSync = prefs.getBoolean(KEY_ENABLE_CUSTOM_VSYNC, false),
        maxAnisotropy = prefs.getFloat(KEY_MAX_ANISOTROPY, -1f),
        aspectRatio = prefs.getInt(KEY_ASPECT_RATIO, 1),
        antiAliasing = prefs.getInt(KEY_ANTI_ALIASING, 0),
        scalingFilter = prefs.getInt(KEY_SCALING_FILTER, 0),
        scalingFilterLevel = prefs.getInt(KEY_SCALING_FILTER_LEVEL, 80),
        enableTextureRecompression = prefs.getBoolean(KEY_TEXTURE_RECOMPRESSION, false),
        enableMacroHle = prefs.getBoolean(KEY_MACRO_HLE, true),
        enableColorSpacePassthrough = prefs.getBoolean(KEY_COLOR_SPACE, false),
        enableSpirvCompilationOnVulkan = prefs.getBoolean(KEY_SPIRV, true),
        enableFileLog = prefs.getBoolean(KEY_FILE_LOG, false),
        audioVolume = prefs.getFloat(KEY_AUDIO_VOLUME, 1f),
        audioMuted = prefs.getBoolean(KEY_AUDIO_MUTED, false),
        showPerformanceHud = prefs.getBoolean(
            KEY_SHOW_PERFORMANCE_HUD,
            // Migrate from the earlier coarse FPS/info toggles if present.
            prefs.getBoolean(KEY_SHOW_FPS_OVERLAY, true) ||
                prefs.getBoolean(KEY_SHOW_INFO_OVERLAY, true),
        ),
        hudShowFps = prefs.getBoolean(KEY_HUD_SHOW_FPS, true),
        hudShowFrameTime = prefs.getBoolean(KEY_HUD_SHOW_FRAME_TIME, true),
        hudShowFifo = prefs.getBoolean(KEY_HUD_SHOW_FIFO, true),
        hudShowCpuBackend = prefs.getBoolean(KEY_HUD_SHOW_CPU, true),
        hudShowMemory = prefs.getBoolean(KEY_HUD_SHOW_MEMORY, true),
        hudShowGpu = prefs.getBoolean(KEY_HUD_SHOW_GPU, true),
        hudShowPresentedFrames = prefs.getBoolean(KEY_HUD_SHOW_FRAMES, true),
        useSwitchLayout = prefs.getBoolean(KEY_SWITCH_LAYOUT, true),
        showTouchControls = prefs.getBoolean(KEY_TOUCH_CONTROLS, true),
        touchControlsScale = prefs.getFloat(KEY_TOUCH_SCALE, 1f).coerceIn(0.5f, 1.5f),
        touchStickSensitivity = prefs.getFloat(KEY_TOUCH_STICK_SENS, 1f).coerceIn(0.25f, 2f),
        touchControlsOpacity = prefs.getFloat(KEY_TOUCH_OPACITY, 0.4f).coerceIn(0.15f, 1f),
        showTouchRightStick = prefs.getBoolean(KEY_TOUCH_RIGHT_STICK, true),
        touchInvertStickY = prefs.getBoolean(KEY_TOUCH_INVERT_STICK_Y, false),
        enableMotion = prefs.getBoolean(KEY_ENABLE_MOTION, true),
        motionSensitivity = prefs.getFloat(KEY_MOTION_SENS, 1f).coerceIn(0.25f, 2f),
        updatesFolderUri = prefs.getString(KEY_UPDATES_FOLDER_URI, "") ?: "",
        dataFolderMode = prefs.getInt(KEY_DATA_FOLDER_MODE, 0),
        dataFolderCustomPath = prefs.getString(KEY_DATA_FOLDER_CUSTOM_PATH, "") ?: "",
    )

    // commit(): callers (data-folder change) restart the process immediately afterwards,
    // and apply()'s background flush can lose the write when the process is killed.
    fun save(config: EmulatorConfig) = prefs.edit(commit = true) {
        putInt(KEY_MEM_CONFIG, config.memoryConfiguration)
        putInt(KEY_MEM_MODE, config.memoryManagerMode)
        putBoolean(KEY_USE_NCE, config.useNce)
        putInt(KEY_NCE_DEBUG_LEVEL, config.nceDebugLevel.coerceIn(0, 3))
        putInt(KEY_LANGUAGE, config.systemLanguage)
        putInt(KEY_REGION, config.systemRegion)
        putBoolean(KEY_DOCKED, config.dockedMode)
        putBoolean(KEY_PPTC, config.enablePptc)
        putBoolean(KEY_LOW_POWER_PTC, config.enableLowPowerPtc)
        putBoolean(KEY_FS_INTEGRITY, config.enableFsIntegrity)
        putBoolean(KEY_INTERNET, config.enableInternet)
        putBoolean(KEY_IGNORE_MISSING, config.ignoreMissingServices)
        putBoolean(KEY_MATCH_SYSTEM_TIME, config.matchSystemTime)
        putString(KEY_TIME_ZONE, config.timeZone)
        putLong(KEY_SYSTEM_TIME_OFFSET, config.systemTimeOffset)
        putLong(KEY_TICK_SCALAR, config.tickScalar)
        putBoolean(KEY_SHADER_CACHE, config.enableShaderCache)
        putInt(KEY_BACKEND_THREADING, config.backendThreading)
        putFloat(KEY_RES_SCALE, config.resScale)
        putInt(KEY_VSYNC_MODE, config.vsyncMode)
        putInt(KEY_CUSTOM_VSYNC_INTERVAL, config.customVSyncInterval)
        putBoolean(KEY_ENABLE_CUSTOM_VSYNC, config.enableCustomVSync)
        putFloat(KEY_MAX_ANISOTROPY, config.maxAnisotropy)
        putInt(KEY_ASPECT_RATIO, config.aspectRatio)
        putInt(KEY_ANTI_ALIASING, config.antiAliasing)
        putInt(KEY_SCALING_FILTER, config.scalingFilter)
        putInt(KEY_SCALING_FILTER_LEVEL, config.scalingFilterLevel)
        putBoolean(KEY_TEXTURE_RECOMPRESSION, config.enableTextureRecompression)
        putBoolean(KEY_MACRO_HLE, config.enableMacroHle)
        putBoolean(KEY_COLOR_SPACE, config.enableColorSpacePassthrough)
        putBoolean(KEY_SPIRV, config.enableSpirvCompilationOnVulkan)
        putBoolean(KEY_FILE_LOG, config.enableFileLog)
        putFloat(KEY_AUDIO_VOLUME, config.audioVolume)
        putBoolean(KEY_AUDIO_MUTED, config.audioMuted)
        putBoolean(KEY_SHOW_PERFORMANCE_HUD, config.showPerformanceHud)
        putBoolean(KEY_HUD_SHOW_FPS, config.hudShowFps)
        putBoolean(KEY_HUD_SHOW_FRAME_TIME, config.hudShowFrameTime)
        putBoolean(KEY_HUD_SHOW_FIFO, config.hudShowFifo)
        putBoolean(KEY_HUD_SHOW_CPU, config.hudShowCpuBackend)
        putBoolean(KEY_HUD_SHOW_MEMORY, config.hudShowMemory)
        putBoolean(KEY_HUD_SHOW_GPU, config.hudShowGpu)
        putBoolean(KEY_HUD_SHOW_FRAMES, config.hudShowPresentedFrames)
        putBoolean(KEY_SWITCH_LAYOUT, config.useSwitchLayout)
        putBoolean(KEY_TOUCH_CONTROLS, config.showTouchControls)
        putFloat(KEY_TOUCH_SCALE, config.touchControlsScale)
        putFloat(KEY_TOUCH_STICK_SENS, config.touchStickSensitivity)
        putFloat(KEY_TOUCH_OPACITY, config.touchControlsOpacity)
        putBoolean(KEY_TOUCH_RIGHT_STICK, config.showTouchRightStick)
        putBoolean(KEY_TOUCH_INVERT_STICK_Y, config.touchInvertStickY)
        putBoolean(KEY_ENABLE_MOTION, config.enableMotion)
        putFloat(KEY_MOTION_SENS, config.motionSensitivity)
        putString(KEY_UPDATES_FOLDER_URI, config.updatesFolderUri)
        putInt(KEY_DATA_FOLDER_MODE, config.dataFolderMode)
        putString(KEY_DATA_FOLDER_CUSTOM_PATH, config.dataFolderCustomPath)
    }

    /**
     * Retail Switch default is 4 GiB. Larger guest DRAM is an opt-in for texture packs
     * and is unstable on mobile — never auto-select 8/12 GiB.
     * (Enum: 0=4GiB, 1=6GiB, 2=8GiB, 3=12GiB.)
     */
    private fun defaultMemoryConfiguration(): Int = 0

    private companion object {
        const val KEY_MEM_CONFIG = "mem_config"
        const val KEY_MEM_MODE = "mem_mode"
        const val KEY_USE_NCE = "use_nce"
        const val KEY_NCE_DEBUG_LEVEL = "nce_debug_level"
        const val KEY_LANGUAGE = "language"
        const val KEY_REGION = "region"
        const val KEY_DOCKED = "docked"
        const val KEY_PPTC = "pptc"
        const val KEY_LOW_POWER_PTC = "low_power_ptc"
        const val KEY_FS_INTEGRITY = "fs_integrity"
        const val KEY_INTERNET = "internet"
        const val KEY_IGNORE_MISSING = "ignore_missing"
        const val KEY_MATCH_SYSTEM_TIME = "match_system_time"
        const val KEY_TIME_ZONE = "time_zone"
        const val KEY_SYSTEM_TIME_OFFSET = "system_time_offset"
        const val KEY_TICK_SCALAR = "tick_scalar"
        const val KEY_SHADER_CACHE = "shader_cache"
        const val KEY_BACKEND_THREADING = "backend_threading"
        const val KEY_RES_SCALE = "res_scale"
        const val KEY_VSYNC_MODE = "vsync_mode"
        const val KEY_CUSTOM_VSYNC_INTERVAL = "custom_vsync_interval"
        const val KEY_ENABLE_CUSTOM_VSYNC = "enable_custom_vsync"
        const val KEY_MAX_ANISOTROPY = "max_anisotropy"
        const val KEY_ASPECT_RATIO = "aspect_ratio"
        const val KEY_ANTI_ALIASING = "anti_aliasing"
        const val KEY_SCALING_FILTER = "scaling_filter"
        const val KEY_SCALING_FILTER_LEVEL = "scaling_filter_level"
        const val KEY_TEXTURE_RECOMPRESSION = "texture_recompression"
        const val KEY_MACRO_HLE = "macro_hle"
        const val KEY_COLOR_SPACE = "color_space"
        const val KEY_SPIRV = "spirv"
        const val KEY_FILE_LOG = "file_log"
        const val KEY_AUDIO_VOLUME = "audio_volume"
        const val KEY_AUDIO_MUTED = "audio_muted"
        const val KEY_SHOW_PERFORMANCE_HUD = "show_performance_hud"
        // Legacy coarse toggles kept for one-time migration in load().
        const val KEY_SHOW_FPS_OVERLAY = "show_fps_overlay"
        const val KEY_SHOW_INFO_OVERLAY = "show_info_overlay"
        const val KEY_HUD_SHOW_FPS = "hud_show_fps"
        const val KEY_HUD_SHOW_FRAME_TIME = "hud_show_frame_time"
        const val KEY_HUD_SHOW_FIFO = "hud_show_fifo"
        const val KEY_HUD_SHOW_CPU = "hud_show_cpu"
        const val KEY_HUD_SHOW_MEMORY = "hud_show_memory"
        const val KEY_HUD_SHOW_GPU = "hud_show_gpu"
        const val KEY_HUD_SHOW_FRAMES = "hud_show_frames"
        const val KEY_SWITCH_LAYOUT = "switch_layout"
        const val KEY_TOUCH_CONTROLS = "touch_controls"
        const val KEY_TOUCH_SCALE = "touch_scale"
        const val KEY_TOUCH_STICK_SENS = "touch_stick_sens"
        const val KEY_TOUCH_OPACITY = "touch_opacity"
        const val KEY_TOUCH_RIGHT_STICK = "touch_right_stick"
        const val KEY_TOUCH_INVERT_STICK_Y = "touch_invert_stick_y"
        const val KEY_ENABLE_MOTION = "enable_motion"
        const val KEY_MOTION_SENS = "motion_sens"
        const val KEY_UPDATES_FOLDER_URI = "updates_folder_uri"
        const val KEY_DATA_FOLDER_MODE = "data_folder_mode"
        const val KEY_DATA_FOLDER_CUSTOM_PATH = "data_folder_custom_path"
    }
}
