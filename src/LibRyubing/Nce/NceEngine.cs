using ARMeilleure.Memory;
using Ryujinx.Cpu;

namespace LibRyubing.Nce
{
    /// <summary>
    /// NCE (Native Code Execution) CPU engine: runs guest ARM64 code directly
    /// on the host CPU via binary patching and signal-based fault handling,
    /// instead of recompiling it through ARMeilleure.
    ///
    /// Phase 0 stub: implements the <see cref="ICpuEngine"/> surface so the
    /// type graph compiles and is wired into the host, but actual execution
    /// entry points are not yet implemented (they throw). The native backend
    /// (libryubing-nce.so) currently only exports a version query.
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
            // upstream of this call (see AndroidHost wiring in a later phase).
            if (!for64Bit)
            {
                throw new System.NotSupportedException("NCE does not support AArch32 guest code");
            }

            return new NceCpuContext(memoryManager);
        }
    }
}
