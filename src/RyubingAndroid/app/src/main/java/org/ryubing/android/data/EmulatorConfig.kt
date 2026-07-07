package org.ryubing.android.data

/**
 * Mobile-oriented emulator settings. Enum-equivalent ints match the managed side:
 *  - memoryConfiguration -> Ryujinx.HLE.MemoryConfiguration
 *  - memoryManagerMode   -> Ryujinx.Common.Configuration.MemoryManagerMode
 *  - systemLanguage      -> Ryujinx.HLE.HOS.SystemState.SystemLanguage
 *  - systemRegion        -> Ryujinx.HLE.HOS.SystemState.RegionCode
 *  - backendThreading    -> Ryujinx.Common.Configuration.BackendThreading (0=Auto,1=Off,2=On)
 *
 * Defaults are conservative for stability (see docs/kenji-audit-notes.md).
 */
data class EmulatorConfig(
    val memoryConfiguration: Int = 0,   // 4 GiB
    val memoryManagerMode: Int = 2,     // HostMappedUnsafe
    val systemLanguage: Int = 1,        // AmericanEnglish
    val systemRegion: Int = 1,          // USA
    val dockedMode: Boolean = false,
    val enablePptc: Boolean = false,    // off by default on mobile
    val enableShaderCache: Boolean = true,
    val backendThreading: Int = 0,      // Auto
    val resScale: Float = 1f,
)
