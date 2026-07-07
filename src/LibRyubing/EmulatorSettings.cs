using Ryujinx.Common.Configuration;
using Ryujinx.HLE;

namespace LibRyubing
{
    /// <summary>
    /// The subset of Ryubing configuration the Android host needs to start a title.
    /// Populated by the Kotlin settings UI and passed in via the native ABI. Defaults
    /// are deliberately conservative for mobile stability (see docs/kenji-audit-notes.md).
    /// </summary>
    internal sealed class EmulatorSettings
    {
        /// <summary>Guest DRAM size. 4 GiB is the retail Switch default.</summary>
        public MemoryConfiguration MemoryConfiguration { get; set; } = MemoryConfiguration.MemoryConfiguration4GiB;

        /// <summary>Values match <c>Ryujinx.HLE.HOS.SystemState.SystemLanguage</c>.</summary>
        public int SystemLanguage { get; set; } = 1; // AmericanEnglish

        /// <summary>Values match <c>Ryujinx.HLE.HOS.SystemState.RegionCode</c>.</summary>
        public int SystemRegion { get; set; } = 1; // USA

        public VSyncMode VSyncMode { get; set; } = VSyncMode.Switch;

        public bool EnableDockedMode { get; set; } = false;

        /// <summary>PPTC is off by default on Android (memory + startup cost).</summary>
        public bool EnablePtc { get; set; } = false;

        public bool EnableFsIntegrityChecks { get; set; } = true;

        public bool EnableShaderCache { get; set; } = true;

        /// <summary>HostMappedUnsafe is fastest; downgrade to HostMapped for correctness.</summary>
        public MemoryManagerMode MemoryManagerMode { get; set; } = MemoryManagerMode.HostMappedUnsafe;

        public BackendThreading BackendThreading { get; set; } = BackendThreading.Auto;

        public float ResScale { get; set; } = 1f;

        public float AudioVolume { get; set; } = 1f;

        /// <summary>Optional preferred GPU vendor/id; empty means auto-select.</summary>
        public string PreferredGpuId { get; set; } = string.Empty;
    }
}
