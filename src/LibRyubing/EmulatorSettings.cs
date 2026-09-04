using Ryujinx.Common.Configuration;
using Ryujinx.HLE;

namespace LibRyubing
{
    /// <summary>
    /// Subset of Ryubing configuration the Android host needs to start a title.
    /// Populated by the Kotlin settings UI and passed in via the native ABI. Defaults
    /// are deliberately conservative for mobile stability (see docs/kenji-audit-notes.md).
    /// </summary>
    internal sealed class EmulatorSettings
    {
        public MemoryConfiguration MemoryConfiguration { get; set; } = MemoryConfiguration.MemoryConfiguration4GiB;

        /// <summary>Values match <c>Ryujinx.HLE.HOS.SystemState.SystemLanguage</c>.</summary>
        public int SystemLanguage { get; set; } = 1; // AmericanEnglish

        /// <summary>Values match <c>Ryujinx.HLE.HOS.SystemState.RegionCode</c>.</summary>
        public int SystemRegion { get; set; } = 1; // USA

        public VSyncMode VSyncMode { get; set; } = VSyncMode.Switch;

        public int CustomVSyncInterval { get; set; } = 120;

        public bool EnableCustomVSyncInterval { get; set; }

        public bool EnableDockedMode { get; set; }

        /// <summary>PPTC is off by default on Android (memory + startup cost).</summary>
        public bool EnablePtc { get; set; }

        public bool EnableLowPowerPtc { get; set; }

        public bool EnableFsIntegrityChecks { get; set; } = true;

        public bool EnableShaderCache { get; set; } = true;

        public bool EnableInternetAccess { get; set; }

        public bool IgnoreMissingServices { get; set; }

        public bool MatchSystemTime { get; set; }

        public string TimeZone { get; set; } = "UTC";

        public long SystemTimeOffset { get; set; }

        /// <summary>Turbo tick scalar (percent of realtime). Desktop default is typically 200.</summary>
        public long TickScalar { get; set; } = 200;

        public MemoryManagerMode MemoryManagerMode { get; set; } = MemoryManagerMode.HostMappedUnsafe;

        /// <summary>
        /// Prefer Native Code Execution (NCE) on ARM64 host with host-mapped memory.
        /// Default off until homebrew E2E validation; enable via ryubing_set_cpu_config.
        /// </summary>
        public bool UseNce { get; set; }

        public BackendThreading BackendThreading { get; set; } = BackendThreading.Auto;

        public float ResScale { get; set; } = 1f;

        public float MaxAnisotropy { get; set; } = -1f;

        public AspectRatio AspectRatio { get; set; } = AspectRatio.Fixed16x9;

        public AntiAliasing AntiAliasing { get; set; } = AntiAliasing.None;

        public ScalingFilter ScalingFilter { get; set; } = ScalingFilter.Bilinear;

        public int ScalingFilterLevel { get; set; } = 80;

        public bool EnableTextureRecompression { get; set; }

        public bool EnableMacroHLE { get; set; } = true;

        public bool EnableColorSpacePassthrough { get; set; }

        /// <summary>
        /// Generate shaders directly as SPIR-V. Disable to fall back to the GLSL path on
        /// drivers whose compilers reject the direct SPIR-V output.
        /// </summary>
        public bool EnableSpirvCompilationOnVulkan { get; set; } = true;

        public bool EnableFileLog { get; set; }

        public float AudioVolume { get; set; } = 1f;

        /// <summary>Optional preferred GPU vendor/id; empty means auto-select.</summary>
        public string PreferredGpuId { get; set; } = string.Empty;
    }
}
