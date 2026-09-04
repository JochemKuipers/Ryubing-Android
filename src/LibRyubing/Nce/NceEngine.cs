using ARMeilleure.Memory;
using Ryujinx.Cpu;

namespace LibRyubing.Nce
{
    /// <summary>
    /// NCE (Native Code Execution) CPU engine: runs guest ARM64 code directly
    /// on the host CPU via binary patching and signal-based fault handling,
    /// instead of recompiling it through ARMeilleure / LightningJit.
    ///
    /// Selected by <c>ArmProcessContextFactory</c> when <c>HleConfiguration.UseNce</c>
    /// is set and HostMapped + ARM64 host + 64-bit guest prerequisites hold.
    /// </summary>
    public sealed class NceEngine : ICpuEngine
    {
        private readonly ITickSource _tickSource;
        private readonly bool _available;

        public NceEngine(ITickSource tickSource)
        {
            _tickSource = tickSource;
            _available = NceNative.CheckAvailable();
        }

        /// <summary>True when the native NCE backend is loaded and ABI-compatible.</summary>
        public bool IsAvailable => _available;

        /// <inheritdoc/>
        public ICpuContext CreateCpuContext(IMemoryManager memoryManager, bool for64Bit)
        {
            // NCE only supports 64-bit guest code; 32-bit falls back to the JIT engine
            // upstream of this call (see ArmProcessContextFactory wiring in phase 4).
            if (!for64Bit)
            {
                throw new System.NotSupportedException("NCE does not support AArch32 guest code");
            }

            return new NceCpuContext(memoryManager);
        }
    }
}
