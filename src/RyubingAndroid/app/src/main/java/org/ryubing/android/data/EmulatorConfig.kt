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
    val enableFileLog: Boolean = false,
    val audioVolume: Float = 1f,
    /** When true, face buttons follow Switch layout (recommended for Nintendo titles). */
    val useSwitchLayout: Boolean = true,
    val showTouchControls: Boolean = true,
    /** SAF tree URI for auto-discovered updates/DLC folders. */
    val updatesFolderUri: String = "",
)
