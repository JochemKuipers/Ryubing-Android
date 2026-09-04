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
    /// Guest SIGSEGV inside the identity window is handled in-signal via
    /// <see cref="MemoryTracking.VirtualMemoryEvent"/> (GPU dirty / remapping),
    /// registered through <c>nce_set_memory_config</c>.
    ///
    /// Address model (Eden direct-map): the guest address space is identity
    /// mapped, i.e. every guest VA is the host pointer. The kernel laid the
    /// process out inside the reserved window (<see cref="MemoryManagerHostNoMirror.WindowBase"/>),
    /// so PC/SP/TPIDR/GPRs and every pointer in guest memory are used as-is by
    /// native code, HLE and the fault handler alike. There is no translation step.
    /// </summary>
    internal sealed class NceCpuContext : ICpuContext
    {
        private readonly IMemoryManager _memory;
        private readonly MemoryManagerHostNoMirror _identityMemory;
        private readonly MemoryTracking _tracking;
        private readonly NceNative.PageFaultHandler _pageFaultHandler;
        private readonly ulong _windowBase;
        private readonly ulong _windowSize;
        private readonly NceTrace _trace = new();
        private int _haltLogRemaining = 32;

        public NceCpuContext(IMemoryManager memory)
        {
            _memory = memory;
            _pageFaultHandler = OnPageFault;

            if (memory is MemoryManagerHostNoMirror { IsIdentityMapped: true } identity)
            {
                _identityMemory = identity;
                _tracking = identity.Tracking;
                _windowBase = identity.WindowBase;
                _windowSize = identity.WindowSize;
            }
            else
            {
                throw new InvalidOperationException(
                    $"NCE requires an identity-mapped {nameof(MemoryManagerHostNoMirror)} (got {memory.GetType().Name}); " +
                    "ArmProcessContextFactory must consume HleConfiguration.NceAddressSpaceWindow");
            }

            IntPtr handlerPtr = Marshal.GetFunctionPointerForDelegate(_pageFaultHandler);

            NceNative.SetMemoryConfig(_windowBase, _windowSize, handlerPtr);

            if (NceTrace.Standard)
            {
                Logger.Info?.Print(LogClass.Cpu,
                    $"NCE|MEMCFG base=0x{_windowBase:X} size=0x{_windowSize:X} end=0x{_windowBase + _windowSize:X} " +
                    $"pageTablePointer=0x{(ulong)(nint)memory.PageTablePointer:X} native={NceNative.VersionString}");
            }
        }

        /// <summary>
        /// Called from the native SIGSEGV guest path with the faulting guest VA
        /// (== host address). Drives MemoryTracking like NativeSignalHandler does for the JIT.
        /// </summary>
        private int OnPageFault(ulong guestVa, ulong size, int isWrite)
        {
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

                // VirtualMemoryEvent's orphan-protection path restores ReadAndWrite only.
                // NCE instruction fetches need PROT_EXEC; without it we storm on prefetch.
                if (handled && isWrite == 0)
                {
                    _identityMemory.Reprotect(addressAligned, sizeAligned, MemoryPermission.ReadWriteExecute);
                }

                if (NceTrace.Verbose)
                {
                    Logger.Info?.Print(LogClass.Cpu,
                        $"NCE|PAGEFAULT va=0x{guestVa:X} write={isWrite} handled={(handled ? 1 : 0)}");
                }

                return handled ? 1 : 0;
            }
            catch (Exception ex)
            {
                if (NceTrace.Errors)
                {
                    Logger.Error?.Print(LogClass.Cpu, $"NCE|PAGEFAULT handler failed va=0x{guestVa:X}: {ex.Message}");
                }

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
                if (NceTrace.Errors)
                {
                    Logger.Error?.Print(LogClass.Cpu, "NCE|RUN core creation failed");
                }
                return;
            }

            nceContext.SetPc(address);
            CheckEntryState(nceContext);
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

        private void CheckEntryState(NceExecutionContext ctx)
        {
            if (!NceTrace.Errors)
            {
                return;
            }

            ulong pc = ctx.Pc;
            ulong sp = ctx.GetX(31);

            if (!InWindow(pc))
            {
                Logger.Error?.Print(LogClass.Cpu,
                    $"NCE|RUN entry PC 0x{pc:X} is outside the identity window [0x{_windowBase:X}, 0x{_windowBase + _windowSize:X})");
            }
            else if (!_memory.IsMapped(pc))
            {
                Logger.Error?.Print(LogClass.Cpu, $"NCE|RUN entry PC 0x{pc:X} is not mapped");
            }

            // Empty descending stacks: SP is often the exclusive top of the mapped region.
            if (!InWindow(sp))
            {
                Logger.Error?.Print(LogClass.Cpu,
                    $"NCE|RUN entry SP 0x{sp:X} is outside the identity window [0x{_windowBase:X}, 0x{_windowBase + _windowSize:X})");
            }
            else if (!_memory.IsMapped(sp) && !(sp > 0 && _memory.IsMapped(sp - 1)))
            {
                Logger.Error?.Print(LogClass.Cpu, $"NCE|RUN entry SP 0x{sp:X} (and SP-1) are not mapped");
            }
        }

        private bool InWindow(ulong va) => va >= _windowBase && va < _windowBase + _windowSize;

        private void RunLoop(NceExecutionContext nceContext, int coreHandle)
        {
            NceTrace.Run("enter", nceContext);

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
                    string kind = (hr & NceNative.HaltReasonPrefetchAbort) != 0 ? "prefetch" : "data";
                    NceTrace.Fault(kind, hr, nceContext);
                    _trace.DumpRecent($"fault:{kind}", nceContext);
                    NceTrace.Run("exit:fault", nceContext);
                    return;
                }

                if ((hr & NceNative.HaltReasonBreakLoop) != 0)
                {
                    if (nceContext.StopRequested)
                    {
                        NceTrace.Run("exit:stop", nceContext);
                        return;
                    }

                    nceContext.PullFromNative();
                    if (NceTrace.Verbose && _haltLogRemaining > 0)
                    {
                        _haltLogRemaining--;
                        Logger.Info?.Print(LogClass.Cpu, $"NCE|HALT break-loop pc=0x{nceContext.Pc:X}");
                    }

                    nceContext.Callbacks.InterruptCallback?.Invoke(nceContext);

                    if (nceContext.StopRequested)
                    {
                        NceTrace.Run("exit:stop", nceContext);
                        return;
                    }

                    nceContext.PushToNative();
                    continue;
                }

                // hr==0: interrupt race (BreakLoop bit cleared before the signal
                // frame returned it). Guest PC is still valid — keep running.
                if (hr == 0)
                {
                    nceContext.PullFromNative();
                    if (_haltLogRemaining > 0)
                    {
                        _haltLogRemaining--;
                        NceTrace.Halt("spurious", hr, nceContext);
                    }

                    if (nceContext.StopRequested)
                    {
                        NceTrace.Run("exit:stop", nceContext);
                        return;
                    }

                    nceContext.PushToNative();
                    continue;
                }

                nceContext.PullFromNative();
                NceTrace.Halt("unexpected", hr, nceContext);
                NceTrace.Run("exit:unexpected", nceContext);
                return;
            }
        }

        private void HandleSupervisorCall(NceExecutionContext nceContext, int coreHandle)
        {
            nceContext.PullFromNative();

            uint svcNumber = NceNative.GetSvcNumber(coreHandle);
            ulong svcAddress = nceContext.Pc - 4;

            // Capture before HLE overwrites X0 with Result.
            ulong x0 = nceContext.GetX(0);
            ulong x1 = nceContext.GetX(1);
            ulong x2 = nceContext.GetX(2);
            ulong x3 = nceContext.GetX(3);

            // Break throws GuestBrokeExecutionException — log before HLE runs.
            if (svcNumber == 0x26)
            {
                LogGuestBreak(nceContext, x0, x1, x2);
            }

            nceContext.Callbacks.SupervisorCallback?.Invoke(nceContext, svcAddress, (int)svcNumber);

            ulong result = nceContext.GetX(0);
            var record = new NceSvcRecord(svcAddress, svcNumber, x0, x1, x2, x3, result);

            if (_trace.Svc(record) && svcNumber == 0x6 && x0 != 0)
            {
                // QueryMemory storm: show what the kernel keeps answering.
                LogQueryMemoryInfo(x0, x1);
                _trace.DumpRecent("storm", nceContext);
            }

            // Memory-shaping SVC failures are the most useful bring-up signal; always log them.
            if (result != 0 && NceTrace.Errors && IsMemorySvc(svcNumber))
            {
                Logger.Warning?.Print(LogClass.Cpu, $"NCE|SVCFAIL {record}");
            }

            nceContext.PushToNative();
        }

        private static bool IsMemorySvc(uint svc) => svc switch
        {
            0x1 or 0x2 or 0x3 or 0x4 or 0x5 or 0x13 or 0x14 or 0x15 or 0x2C or 0x2D or 0x74 or 0x75 or 0x77 or 0x78 => true,
            _ => false,
        };

        private void LogQueryMemoryInfo(ulong infoPtr, ulong queried)
        {
            try
            {
                if (!_memory.IsMapped(infoPtr))
                {
                    return;
                }

                // MemoryInfo layout: ulong Address, ulong Size, uint State, uint Attribute, uint Permission ...
                ulong addr = _memory.Read<ulong>(infoPtr);
                ulong size = _memory.Read<ulong>(infoPtr + 8);
                uint state = _memory.Read<uint>(infoPtr + 16);
                uint attr = _memory.Read<uint>(infoPtr + 20);
                uint perm = _memory.Read<uint>(infoPtr + 24);

                Logger.Warning?.Print(LogClass.Cpu,
                    $"NCE|QM queried=0x{queried:X} info.addr=0x{addr:X} info.size=0x{size:X} info.end=0x{addr + size:X} " +
                    $"state=0x{state:X} attr=0x{attr:X} perm=0x{perm:X} contains={(queried >= addr && queried < addr + size ? 1 : 0)}");
            }
            catch (Exception ex)
            {
                Logger.Warning?.Print(LogClass.Cpu, $"NCE|QM read failed at 0x{infoPtr:X}: {ex.Message}");
            }
        }

        private void LogGuestBreak(NceExecutionContext ctx, ulong reason, ulong resultPtr, ulong size)
        {
            if (!NceTrace.Errors)
            {
                return;
            }

            try
            {
                if (size >= 4 && resultPtr != 0 && _memory.IsMapped(resultPtr))
                {
                    uint result = _memory.Read<uint>(resultPtr);
                    Logger.Error?.Print(LogClass.Cpu,
                        $"NCE|BREAK reason=0x{reason:X} Result=0x{result:X8} at 0x{resultPtr:X}");
                }
                else
                {
                    Logger.Error?.Print(LogClass.Cpu,
                        $"NCE|BREAK reason=0x{reason:X} resultPtr=0x{resultPtr:X} size=0x{size:X}");
                }
            }
            catch (Exception ex)
            {
                Logger.Error?.Print(LogClass.Cpu, $"NCE|BREAK (failed to read Result): {ex.Message}");
            }

            _trace.DumpRecent("break", ctx);
        }

        /// <inheritdoc/>
        public void InvalidateCacheRegion(ulong address, ulong size)
        {
            // NCE has no JIT cache; guest IC IVAU works natively.
        }

        /// <inheritdoc/>
        public IDiskCacheLoadState LoadDiskCache(PtcCacheInfo cacheInfo, bool enabled)
        {
            return null;
        }

        /// <inheritdoc/>
        public void PrepareCodeRange(ulong address, ulong size)
        {
            // NCE patches at load; nothing to prepare.
        }

        public void Dispose()
        {
            // The delegate is kept alive by this instance for the lifetime of the process;
            // the native side is told to drop it so a late fault cannot call into a dead handler.
            NceNative.SetMemoryConfig(0, 0, IntPtr.Zero);
        }
    }
}
