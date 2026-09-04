using System;
using System.Runtime.InteropServices;
using ARMeilleure.Memory;
using ARMeilleure.Translation.PTC;
using Ryujinx.Cpu;
using Ryujinx.Cpu.Jit;
using Ryujinx.Common.Logging;
using Ryujinx.Memory;
using Ryujinx.Memory.Tracking;

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
    ///
    /// Guest SIGSEGV inside the host-mapped AS is handled in-signal via
    /// <see cref="MemoryTracking.VirtualMemoryEvent"/> (GPU dirty / remapping),
    /// registered through <c>nce_set_memory_config</c>.
    /// </summary>
    internal sealed class NceCpuContext : ICpuContext
    {
        private readonly IMemoryManager _memory;
        private readonly MemoryTracking _tracking;
        private readonly NceNative.PageFaultHandler _pageFaultHandler;

        // Active context for the UnmanagedCallersOnly-style delegate target.
        // One guest process / address space at a time under NCE.
        private static NceCpuContext s_active;

        public NceCpuContext(IMemoryManager memory)
        {
            _memory = memory;
            _tracking = ResolveTracking(memory);
            _pageFaultHandler = OnPageFault;

            // Register host-mapped AS so guest SIGSEGV can resolve GPU-protected pages.
            ulong hostBase = (ulong)(nint)memory.PageTablePointer;
            ulong asSize = 1UL << memory.AddressSpaceBits;
            IntPtr handlerPtr = _tracking != null
                ? Marshal.GetFunctionPointerForDelegate(_pageFaultHandler)
                : IntPtr.Zero;

            NceNative.SetMemoryConfig(hostBase, asSize, handlerPtr);
            s_active = this;

            if (_tracking == null)
            {
                Logger.Warning?.Print(LogClass.Cpu,
                    "NCE: memory manager has no MemoryTracking; guest page faults will skip/abort only");
            }
        }

        private static MemoryTracking ResolveTracking(IMemoryManager memory)
        {
            return memory switch
            {
                MemoryManagerHostMapped mapped => mapped.Tracking,
                MemoryManagerHostNoMirror noMirror => noMirror.Tracking,
                _ => null,
            };
        }

        /// <summary>
        /// Called from the native SIGSEGV guest path (same pattern as JIT
        /// NativeSignalHandler → TrackingEventDelegate).
        /// </summary>
        private int OnPageFault(ulong guestVa, ulong size, int isWrite)
        {
            if (_tracking == null)
            {
                return 0;
            }

            try
            {
                ulong pageSize = MemoryBlock.GetPageSize();
                ulong addressAligned = guestVa & ~(pageSize - 1);
                ulong sizeAligned = pageSize;

                if (size > pageSize)
                {
                    sizeAligned = (size + pageSize - 1) & ~(pageSize - 1);
                }

                bool handled = _tracking.VirtualMemoryEvent(addressAligned, sizeAligned, isWrite != 0);
                return handled ? 1 : 0;
            }
            catch (Exception ex)
            {
                Logger.Error?.Print(LogClass.Cpu, $"NCE page fault handler failed: {ex.Message}");
                return 0;
            }
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
                ulong hr = NceNative.RunThread(coreHandle, 0);

                if ((hr & NceNative.HaltReasonSupervisorCall) != 0)
                {
                    HandleSupervisorCall(nceContext, coreHandle);
                    continue;
                }

                if ((hr & (NceNative.HaltReasonDataAbort | NceNative.HaltReasonPrefetchAbort)) != 0)
                {
                    nceContext.PullFromNative();
                    Logger.Error?.Print(LogClass.Cpu,
                        $"NCE: guest fault at pc=0x{nceContext.Pc:X16} (hr=0x{hr:X16})");
                    return;
                }

                if ((hr & NceNative.HaltReasonBreakLoop) != 0)
                {
                    if (nceContext.StopRequested)
                    {
                        return;
                    }

                    nceContext.PullFromNative();
                    nceContext.Callbacks.InterruptCallback?.Invoke(nceContext);

                    if (nceContext.StopRequested)
                    {
                        return;
                    }

                    nceContext.PushToNative();
                    continue;
                }

                if (hr != 0)
                {
                    nceContext.PullFromNative();
                }
                return;
            }
        }

        private static void HandleSupervisorCall(NceExecutionContext nceContext, int coreHandle)
        {
            nceContext.PullFromNative();
            uint svcNumber = NceNative.GetSvcNumber(coreHandle);
            ulong svcAddress = nceContext.Pc - 4;
            nceContext.Callbacks.SupervisorCallback?.Invoke(nceContext, svcAddress, (int)svcNumber);
            nceContext.PushToNative();
        }

        /// <inheritdoc/>
        public void InvalidateCacheRegion(ulong address, ulong size)
        {
            // NCE has no JIT cache; guest IC IVAU works natively.
        }

        /// <inheritdoc/>
        public IDiskCacheLoadState LoadDiskCache(PtcCacheInfo cacheInfo, bool enabled)
        {
            return new DummyDiskCacheLoadState();
        }

        /// <inheritdoc/>
        public void PrepareCodeRange(ulong address, ulong size)
        {
            // Patching happens at load time in ProcessLoaderHelper / IRoInterface.
        }

        public void Dispose()
        {
            if (ReferenceEquals(s_active, this))
            {
                NceNative.SetMemoryConfig(0, 0, IntPtr.Zero);
                s_active = null;
            }

            NceNative.ClearTrampolines();

            // Keep the delegate rooted for the lifetime of the context so the
            // native function pointer stays valid while cores may still run.
            GC.KeepAlive(_pageFaultHandler);
        }
    }
}
