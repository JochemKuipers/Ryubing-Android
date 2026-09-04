using System;
using System.Runtime.InteropServices;

namespace LibRyubing.Nce
{
    /// <summary>
    /// P/Invoke surface into libryubing-nce.so, the native NCE backend.
    ///
    /// The library is loaded eagerly by the Android linker (the JNI shim has a
    /// DT_NEEDED on it), so these DllImports resolve against the already-loaded
    /// soname "libryubing-nce.so".
    /// </summary>
    internal static unsafe class NceNative
    {
        private const string Lib = "ryubing-nce";

        /// <summary>NCE ABI version this managed side expects (see nce.h).</summary>
        internal const int ExpectedAbiVersion = 4;

        [DllImport(Lib, EntryPoint = "nce_get_abi_version")]
        internal static extern int GetAbiVersion();

        [DllImport(Lib, EntryPoint = "nce_get_version_string")]
        internal static extern byte* GetVersionString();

        /// <summary>Native library version string, or "unavailable".</summary>
        internal static string VersionString
        {
            get
            {
                try
                {
                    byte* p = GetVersionString();
                    return p == null ? "unavailable" : Marshal.PtrToStringUTF8((IntPtr)p) ?? "unavailable";
                }
                catch (DllNotFoundException)
                {
                    return "unavailable";
                }
                catch (EntryPointNotFoundException)
                {
                    return "unavailable";
                }
            }
        }

        [DllImport(Lib, EntryPoint = "nce_set_debug_level")]
        internal static extern void SetDebugLevel(int level);

        /// <summary>Cached managed copy of the native debug level (0–3).</summary>
        internal static int DebugLevel { get; private set; }

        /// <summary>
        /// Applies NCE debug verbosity to both managed gating and the native library.
        /// Safe to call before the .so is loaded (native call is best-effort).
        /// </summary>
        internal static void ApplyDebugLevel(int level)
        {
            DebugLevel = Math.Clamp(level, 0, 3);
            try
            {
                SetDebugLevel(DebugLevel);
            }
            catch (DllNotFoundException)
            {
                // libryubing-nce not loaded yet; CheckAvailable / ctor will retry.
            }
            catch (EntryPointNotFoundException)
            {
            }
        }

        internal static bool LogErrors => DebugLevel >= 1;
        internal static bool LogStandard => DebugLevel >= 2;
        internal static bool LogVerbose => DebugLevel >= 3;

        // --- Halt reasons (must match HaltReason in guest_context.h) ---

        internal const ulong HaltReasonStepThread = 0x00000001;
        internal const ulong HaltReasonDataAbort = 0x00000004;
        internal const ulong HaltReasonBreakLoop = 0x02000000;
        internal const ulong HaltReasonSupervisorCall = 0x04000000;
        internal const ulong HaltReasonInstructionBreakpoint = 0x08000000;
        internal const ulong HaltReasonPrefetchAbort = 0x20000000;

        // --- Guest context view (must match NceGuestContextView in nce.h) ---

        [StructLayout(LayoutKind.Sequential)]
        internal unsafe struct NceGuestContextView
        {
            public fixed ulong X[31];   // X0-X30
            public ulong Sp;
            public ulong Pc;
            public uint Pstate;
            public uint Fpcr;
            public uint Fpsr;
            public ulong TpidrEl0;
            public ulong TpidrroEl0;
            // V0-V31 as (e0, e1) ulong pairs — matches ARMeilleure.State.V128 layout.
            public fixed ulong V[64];

            /// <summary>
            /// Reads general-purpose register X[index] (0-30), or SP when index is 31
            /// (matches <see cref="Ryujinx.Cpu.IExecutionContext"/>).
            /// </summary>
            public ulong GetX(int index)
            {
                if ((uint)index > 31)
                {
                    throw new System.ArgumentOutOfRangeException(nameof(index));
                }

                if (index == 31)
                {
                    return Sp;
                }

                fixed (ulong* p = X)
                {
                    return p[index];
                }
            }

            /// <summary>
            /// Writes general-purpose register X[index] (0-30), or SP when index is 31.
            /// </summary>
            public void SetX(int index, ulong value)
            {
                if ((uint)index > 31)
                {
                    throw new System.ArgumentOutOfRangeException(nameof(index));
                }

                if (index == 31)
                {
                    Sp = value;
                    return;
                }

                fixed (ulong* p = X)
                {
                    p[index] = value;
                }
            }

            /// <summary>Reads FP/SIMD register V[index] (0-31).</summary>
            public ARMeilleure.State.V128 GetV(int index)
            {
                if ((uint)index > 31)
                {
                    throw new System.ArgumentOutOfRangeException(nameof(index));
                }

                fixed (ulong* p = V)
                {
                    return new ARMeilleure.State.V128(p[index * 2], p[index * 2 + 1]);
                }
            }

            /// <summary>Writes FP/SIMD register V[index] (0-31).</summary>
            public void SetV(int index, ARMeilleure.State.V128 value)
            {
                if ((uint)index > 31)
                {
                    throw new System.ArgumentOutOfRangeException(nameof(index));
                }

                fixed (ulong* p = V)
                {
                    p[index * 2] = value.Extract<ulong>(0);
                    p[index * 2 + 1] = value.Extract<ulong>(1);
                }
            }
        }

        // --- Thread parameters are NOT exposed to managed code ---
        // The native core owns them at a stable address (the guest's
        // TPIDR_EL0 points there while running); a GC-movable managed copy
        // would be unsafe. The C ABI only uses the core handle.

        // --- Signal handling and core management (phase 2/3) ---

        [DllImport(Lib, EntryPoint = "nce_initialize")]
        internal static extern int Initialize();

        [DllImport(Lib, EntryPoint = "nce_thread_init")]
        internal static extern int ThreadInit();

        [DllImport(Lib, EntryPoint = "nce_core_create")]
        internal static extern int CoreCreate();

        [DllImport(Lib, EntryPoint = "nce_core_destroy")]
        internal static extern void CoreDestroy(int coreHandle);

        [DllImport(Lib, EntryPoint = "nce_run_thread")]
        internal static extern ulong RunThread(int coreHandle, ulong trampolineAddr);

        [DllImport(Lib, EntryPoint = "nce_signal_interrupt")]
        internal static extern void SignalInterrupt(int coreHandle);

        [DllImport(Lib, EntryPoint = "nce_get_context")]
        internal static extern void GetContext(int coreHandle, ref NceGuestContextView view);

        [DllImport(Lib, EntryPoint = "nce_set_context")]
        internal static extern void SetContext(int coreHandle, ref NceGuestContextView view);

        [DllImport(Lib, EntryPoint = "nce_get_svc_number")]
        internal static extern uint GetSvcNumber(int coreHandle);

        [DllImport(Lib, EntryPoint = "nce_clear_trampolines")]
        internal static extern void ClearTrampolines();

        // --- Guest memory / page-fault integration (phase 5) ---

        /// <summary>
        /// Native page-fault callback: return 1 if handled (resume guest), 0 otherwise.
        /// </summary>
        [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
        internal delegate int PageFaultHandler(ulong guestVa, ulong size, int isWrite);

        /// <summary>
        /// Registers the identity window [windowBase, windowBase + windowSize) and the managed
        /// page-fault handler. Guest VAs are host pointers, so the native side passes the
        /// faulting address straight through.
        /// </summary>
        [DllImport(Lib, EntryPoint = "nce_set_memory_config")]
        internal static extern void SetMemoryConfig(ulong windowBase, ulong windowSize, IntPtr handler);

        // --- Self test (must match NceSelfTestResult in nce.h) ---

        [StructLayout(LayoutKind.Sequential)]
        internal struct NceSelfTestResult
        {
            public uint StagesRun;
            public uint StagesFailed;
            public uint ObservedSvcNumber;
            public uint Reserved0;
            public ulong ObservedSvcX0;
            public ulong ObservedStoreValue;
            public ulong ObservedAlignmentValue;
            public ulong ObservedHaltReason;
            public ulong ObservedFaultPc;
            public ulong ScratchAddress;
        }

        /// <summary>
        /// Runs the native self-test using the top of the identity window as scratch.
        /// Returns 0 on success; non-zero on failure (see <see cref="NceSelfTestResult.StagesFailed"/>).
        /// </summary>
        [DllImport(Lib, EntryPoint = "nce_self_test")]
        internal static extern int SelfTest(ulong windowBase, ulong windowSize, ref NceSelfTestResult result);

        // --- Patch result struct (must match NcePatchResult in nce.h) ---

        [StructLayout(LayoutKind.Sequential)]
        internal struct NcePatchResult
        {
            public int Success;
            public ulong PatchedImageSize;
            public ulong PatchOffset;
            public ulong PatchSize;
            public ulong PrePatchOffset;
            public ulong PrePatchSize;
            public uint PatchedSvcCount;
            public uint PatchedSysregCount;
            public uint ConvertedExclusiveCount;
            public uint PatchMode;
        }

        // Patch modes (must match PatchMode in patcher.h).
        internal const uint PatchModeNone = 0;
        internal const uint PatchModePreText = 1;
        internal const uint PatchModePostData = 2;
        internal const uint PatchModeSplit = 3;

        [DllImport(Lib, EntryPoint = "nce_patch_module")]
        private static extern int PatchModuleNative(
            byte* programImage,
            ulong imageCapacity,
            ulong imageSize,
            ulong codeOffset,
            ulong codeSize,
            ulong baseVirtualAddr,
            ulong* outImageSize,
            NcePatchResult* outResult);

        /// <summary>
        /// Two-pass wrapper around nce_patch_module: first queries the required
        /// capacity, then patches into a right-sized managed buffer.
        /// Returns the patched image, or null on failure.
        /// </summary>
        internal static byte[]? PatchModule(
            byte[] programImage, ulong codeOffset, ulong codeSize, ulong baseVirtualAddr,
            out NcePatchResult result)
        {
            result = default;

            if (programImage is null || programImage.Length == 0)
            {
                return null;
            }

            ulong imageSize = (ulong)programImage.Length;

            // Pass 1: query the required capacity.
            if (!TryQueryPatchCapacity(imageSize, codeOffset, codeSize, out ulong requiredSize))
            {
                return null;
            }

            // Allocate a buffer with the required capacity and copy the image in.
            // The native side writes the patched image back into this buffer.
            byte[] buffer = new byte[requiredSize];
            System.Array.Copy(programImage, buffer, programImage.Length);

            // Pass 2: patch.
            ulong finalSize = 0;
            NcePatchResult tmp = default;
            fixed (byte* pBuffer = buffer)
            {
                int rc = PatchModuleNative(pBuffer, requiredSize, imageSize, codeOffset,
                    codeSize, baseVirtualAddr, &finalSize, &tmp);
                if (rc != 0 || tmp.Success == 0)
                {
                    return null;
                }
            }

            result = tmp;

            // Trim to the actual patched size.
            if (finalSize < (ulong)buffer.LongLength)
            {
                System.Array.Resize(ref buffer, (int)finalSize);
            }

            return buffer;
        }

        /// <summary>
        /// Queries the buffer capacity needed to patch a module of the given size
        /// without performing the patch (pass-1 of nce_patch_module).
        /// </summary>
        internal static bool TryQueryPatchCapacity(
            ulong imageSize, ulong codeOffset, ulong codeSize, out ulong requiredCapacity)
        {
            requiredCapacity = 0;
            NcePatchResult result = default;
            ulong required = 0;
            int rc = PatchModuleNative(null, 0, imageSize, codeOffset, codeSize,
                0, &required, &result);
            if (rc != 0)
            {
                return false;
            }

            requiredCapacity = required;
            return true;
        }

        /// <summary>
        /// Checks that the native library is loaded and ABI-compatible.
        /// </summary>
        internal static bool CheckAvailable()
        {
            try
            {
                int abi = GetAbiVersion();
                if (abi != ExpectedAbiVersion)
                {
                    Ryujinx.Common.Logging.Logger.Error?.Print(
                        Ryujinx.Common.Logging.LogClass.Application,
                        $"libryubing-nce ABI mismatch: expected {ExpectedAbiVersion}, got {abi}");
                    return false;
                }

                return true;
            }
            catch (System.DllNotFoundException)
            {
                Ryujinx.Common.Logging.Logger.Warning?.Print(
                    Ryujinx.Common.Logging.LogClass.Application,
                    "libryubing-nce.so not found; NCE backend unavailable");
                return false;
            }
        }
    }
}

