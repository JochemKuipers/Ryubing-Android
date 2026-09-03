using System;
using ARMeilleure.Memory;
using ARMeilleure.Translation.PTC;
using Ryujinx.Cpu;

namespace LibRyubing.Nce
{
    /// <summary>
    /// NCE CPU context for one guest address space.
    ///
    /// Phase 0 stub: implements the <see cref="ICpuContext"/> surface so the
    /// type compiles, but the execution entry points are not functional yet.
    /// Later phases wire these to the native backend:
    ///   - Execute              -> nce_run_thread (phase 3)
    ///   - PrepareCodeRange     -> nce_patch_module (phase 1)
    ///   - InvalidateCacheRegion -> nce_invalidate_cache_range (phase 2)
    /// </summary>
    internal sealed class NceCpuContext : ICpuContext
    {
        private readonly IMemoryManager _memory;

        public NceCpuContext(IMemoryManager memory)
        {
            _memory = memory;
        }

        /// <inheritdoc/>
        public IExecutionContext CreateExecutionContext(ExceptionCallbacks exceptionCallbacks)
        {
            return new NceExecutionContext(exceptionCallbacks);
        }

        /// <inheritdoc/>
        public void Execute(IExecutionContext context, ulong address)
        {
            // Phase 3: calls into the native run loop and dispatches SVCs
            // through the managed callback table.
            throw new NotImplementedException("NCE execution is not implemented yet (phase 3)");
        }

        /// <inheritdoc/>
        public void InvalidateCacheRegion(ulong address, ulong size)
        {
            // Phase 2: native instruction-cache invalidation (dsb ish + isb).
            // With NCE the guest runs on the real CPU, so "invalidating the
            // JIT cache" reduces to a memory barrier.
        }

        /// <inheritdoc/>
        public IDiskCacheLoadState LoadDiskCache(PtcCacheInfo cacheInfo, bool enabled)
        {
            // NCE does not recompile code, so there is no PTC disk cache.
            // A future phase may add a "patched image cache" to skip
            // re-patching on subsequent loads; until then, report nothing.
            return new DummyDiskCacheLoadState();
        }

        /// <inheritdoc/>
        public void PrepareCodeRange(ulong address, ulong size)
        {
            // Phase 1: records the code range and (once the patcher lands)
            // runs nce_patch_module on the segment before it is written
            // into guest memory.
        }

        public void Dispose()
        {
            // Nothing to dispose in the stub; the native core handles are
            // destroyed by nce_core_destroy (phase 3).
        }
    }
}
