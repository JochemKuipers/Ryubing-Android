package org.ryubing.android.data

/**
 * Mobile-oriented emulator settings. Enum-equivalent ints match the managed side:
 *  - memoryConfiguration -> Ryujinx.HLE.MemoryConfiguration
 *  - memoryManagerMode   -> Ryujinx.Common.Configuration.MemoryManagerMode
 *  - systemLanguage      -> Ryujinx.HLE.HOS.SystemState.SystemLanguage
 *  - systemRegion        -> Ryujinx.HLE.HOS.SystemState.RegionCode
 *  - backendThreading    -> Ryujinx.Common.Configuration.BackendThreading (0=Auto,1=Off,2=On)
 *  - vsyncMode           -> Ryujinx.Common.Configuration.VSyncMode (0=Switch,1=Unbounded,2=Custom)
 *  - aspectRatio         -> Ryujinx.Common.Configuration.AspectRatio (1=Fixed16x9)
 *  - antiAliasing        -> Ryujinx.Common.Configuration.AntiAliasing
 *  - scalingFilter       -> Ryujinx.Common.Configuration.ScalingFilter
 *
 * Defaults are conservative for stability (see docs/kenji-audit-notes.md / EmulatorSettings.cs).
 */
data class EmulatorConfig(
    val memoryConfiguration: Int = 0,   // 4 GiB
    val memoryManagerMode: Int = 2,     // HostMappedUnsafe
    /** Prefer NCE (Native Code Execution) on ARM64 + HostMapped. Default off. */
    val useNce: Boolean = false,
    /**
     * NCE debug verbosity (Eden-style): 0=Off, 1=Errors, 2=Standard, 3=Verbose.
     * Default Verbose while bringing up NCE on device.
     */
    val nceDebugLevel: Int = 3,
    val systemLanguage: Int = 1,        // AmericanEnglish
    val systemRegion: Int = 1,          // USA
    val dockedMode: Boolean = false,
    val enablePptc: Boolean = false,    // off by default on mobile
    val enableLowPowerPtc: Boolean = false,
    val enableFsIntegrity: Boolean = true,
    val enableInternet: Boolean = false,
    val ignoreMissingServices: Boolean = false,
    val matchSystemTime: Boolean = false,
    val timeZone: String = "UTC",
    val systemTimeOffset: Long = 0L,
    val tickScalar: Long = 200L,
    val enableShaderCache: Boolean = true,
    val backendThreading: Int = 0,      // Auto
    val resScale: Float = 1f,
    val vsyncMode: Int = 0,             // Switch
    val customVSyncInterval: Int = 120,
    val enableCustomVSync: Boolean = false,
    val maxAnisotropy: Float = -1f,
    val aspectRatio: Int = 1,           // Fixed16x9
    val antiAliasing: Int = 0,
    val scalingFilter: Int = 0,
    val scalingFilterLevel: Int = 80,
    val enableTextureRecompression: Boolean = false,
    val enableMacroHle: Boolean = true,
    val enableColorSpacePassthrough: Boolean = false,
    /**
     * Generate shaders directly as SPIR-V (upstream default). Turning this off falls back
     * to the GLSL translation path — a workaround for vendor drivers that crash compiling
     * the direct SPIR-V output (e.g. some Qualcomm compute shaders).
     */
    val enableSpirvCompilationOnVulkan: Boolean = true,
    val enableFileLog: Boolean = false,
    val audioVolume: Float = 1f,
    val audioMuted: Boolean = false,
    /** When true, face buttons follow Switch layout (recommended for Nintendo titles). */
    val useSwitchLayout: Boolean = true,
    val showTouchControls: Boolean = true,
    /** On-screen pad size multiplier (0.5–1.5). */
    val touchControlsScale: Float = 1f,
    /** Multiplier applied to touch analog stick output (0.25–2). */
    val touchStickSensitivity: Float = 1f,
    /** Overlay button/stick visibility (0.15–1). */
    val touchControlsOpacity: Float = 0.4f,
    val showTouchRightStick: Boolean = true,
    val touchInvertStickY: Boolean = false,
    val enableMotion: Boolean = true,
    /** Multiplier applied to device gyro/accel fed to the guest (0.25–2). */
    val motionSensitivity: Float = 1f,
    /** SAF tree URI for auto-discovered updates/DLC folders. */
    val updatesFolderUri: String = "",
    /** Data folder mode: 0 = internal (filesDir), 1 = Android/data external files dir, 2 = custom. */
    val dataFolderMode: Int = 0,
    /** Custom data folder: plain filesystem path (or content:// tree URI, resolved at startup). */
    val dataFolderCustomPath: String = "",
)
