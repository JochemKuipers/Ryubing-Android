using System;
using ARMeilleure.Memory;
using ARMeilleure.Translation.PTC;
using Ryujinx.Cpu;
using Ryujinx.Common.Logging;

namespace LibRyubing.Nce
{
    /// <summary>
    /// NCE CPU context for one guest address space.
    ///
    /// Execute() drives the native run loop and dispatches guest events:
    ///  - SupervisorCall: pulls the register snapshot (SVC number + args in
    ///    X0-X7), invokes the HLE SupervisorCallback, pushes results back,
    ///    and resumes the guest through the registered SVC trampoline.
    ///  - BreakLoop: scheduler interrupt or stop request; invokes
    ///    InterruptCallback and resumes unless StopRunning was called.
    ///  - DataAbort/PrefetchAbort: fatal guest fault; exits the loop.
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
            var nceContext = (NceExecutionContext)context;

            // Bind the native core to this thread (gettid + sigaltstack).
            nceContext.EnsureCoreCreated();
            int coreHandle = nceContext.CoreHandle;
            if (coreHandle < 0)
            {
                Logger.Error?.Print(LogClass.Cpu, "NCE: failed to create native core");
                return;
            }

            // Set the entry point and publish the initial register state.
            nceContext.SetPc(address);
            nceContext.PushToNative();

            nceContext.SetRunning(true);
            try
            {
                RunLoop(nceContext, coreHandle);
            }
            finally
            {
                nceContext.SetRunning(false);
                nceContext.StopRequested = false;
            }
        }

        private static void RunLoop(NceExecutionContext nceContext, int coreHandle)
        {
            while (true)
            {
                // Run the guest until it exits (SVC, interrupt, or fault).
                // Trampoline auto-lookup: the native side checks the current
                // PC against the trampoline registry (populated by the
                // patcher) and re-enters via the fast path when resuming
                // right after an SVC.
                ulong hr = NceNative.RunThread(coreHandle, 0);

                // SupervisorCall: the guest hit a patched SVC instruction.
                if ((hr & NceNative.HaltReasonSupervisorCall) != 0)
                {
                    HandleSupervisorCall(nceContext, coreHandle);
                    continue; // Resume the guest.
                }

                // BreakLoop: scheduler interrupt or stop request.
                if ((hr & NceNative.HaltReasonBreakLoop) != 0)
                {
                    if (nceContext.StopRequested)
                    {
                        return; // Execute() returns — the thread is done.
                    }

                    // Scheduler interrupt: pull state, notify, check stop
                    // again (the callback may decide to stop), then resume.
                    nceContext.PullFromNative();
                    nceContext.Callbacks.InterruptCallback?.Invoke(nceContext);

                    if (nceContext.StopRequested)
                    {
                        return;
                    }

                    nceContext.PushToNative();
                    continue;
                }

                // DataAbort / PrefetchAbort: unrecoverable guest fault.
                if ((hr & (NceNative.HaltReasonDataAbort | NceNative.HaltReasonPrefetchAbort)) != 0)
                {
                    nceContext.PullFromNative();
                    Logger.Error?.Print(LogClass.Cpu,
                        $"NCE: guest fault at pc=0x{nceContext.Pc:X16} (hr=0x{hr:X16})");
                    return;
                }

                // StepThread or unknown halt reason: exit.
                if (hr != 0)
                {
                    nceContext.PullFromNative();
                }
                return;
            }
        }


        private static void HandleSupervisorCall(NceExecutionContext nceContext, int coreHandle)
        {
            // Pull the register snapshot (SVC number, args in X0-X7, PC).
            nceContext.PullFromNative();
            uint svcNumber = NceNative.GetSvcNumber(coreHandle);

            // The SupervisorCallback signature expects the address of the
            // SVC instruction; the trampoline set PC to the instruction
            // after it, so subtract 4.
            ulong svcAddress = nceContext.Pc - 4;

            // Dispatch to the HLE SVC handler (reads/writes registers via
            // the IExecutionContext accessors, which operate on the cached
            // view we just pulled).
            nceContext.Callbacks.SupervisorCallback?.Invoke(nceContext, svcAddress, (int)svcNumber);

            // Publish the (possibly modified) register state back to the
            // native GuestContext before resuming.
            nceContext.PushToNative();
        }

        /// <inheritdoc/>
        public void InvalidateCacheRegion(ulong address, ulong size)
        {
            // With NCE the guest runs on the real CPU; invalidating the
            // "JIT cache" reduces to a memory barrier. Code modification by
            // the guest itself uses IC IVAU which works natively.
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
            // The code-loading integration (calling nce_patch_module before
            // the image is written into guest memory) lands with the HLE
            // loader wiring (phase 4+); the patcher core itself is complete.
        }

        public void Dispose()
        {
            NceNative.ClearTrampolines();
        }
    }
}

