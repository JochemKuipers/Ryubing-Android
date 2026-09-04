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
        internal const int ExpectedAbiVersion = 1;

        [DllImport(Lib, EntryPoint = "nce_get_abi_version")]
        internal static extern int GetAbiVersion();

        [DllImport(Lib, EntryPoint = "nce_get_version_string")]
        internal static extern byte* GetVersionString();

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

            /// <summary>Reads general-purpose register X[index] (0-30).</summary>
            public ulong GetX(int index) { fixed (ulong* p = X) { return p[index]; } }

            /// <summary>Writes general-purpose register X[index] (0-30).</summary>
            public void SetX(int index, ulong value) { fixed (ulong* p = X) { p[index] = value; } }
        }

        // --- Thread parameters (must match NceThreadParameters in nce.h;
        //     layout is fixed by asm_defs.h — do not reorder) ---

        [StructLayout(LayoutKind.Sequential)]
        internal struct NceThreadParameters
        {
            public ulong TpidrEl0;      // +0x00
            public ulong TpidrroEl0;    // +0x08
            public IntPtr NativeContext; // +0x10 (opaque, set by native side)
            public uint Lock;           // +0x18 (1=unlocked, 0=locked)
            public uint IsRunning;      // +0x1C
            public uint Magic;          // +0x20 (guard value)
            public uint Pad;            // +0x24

            /// <summary>TLS magic value identifying valid parameters (asm_defs.h).</summary>
            public const uint TlsMagicValue = 0x555a5559;

            /// <summary>Initializes the parameters with the TLS magic set.</summary>
            public static NceThreadParameters Create()
            {
                return new NceThreadParameters
                {
                    Lock = 1, // SpinLockUnlocked
                    Magic = TlsMagicValue,
                };
            }
        }

        // --- Signal handling and core management (phase 2) ---

        [DllImport(Lib, EntryPoint = "nce_initialize")]
        internal static extern int Initialize();

        [DllImport(Lib, EntryPoint = "nce_thread_init")]
        internal static extern int ThreadInit();

        [DllImport(Lib, EntryPoint = "nce_core_create")]
        internal static extern int CoreCreate();

        [DllImport(Lib, EntryPoint = "nce_core_destroy")]
        internal static extern void CoreDestroy(int coreHandle);

        [DllImport(Lib, EntryPoint = "nce_run_thread")]
        internal static extern ulong RunThread(int coreHandle, ref NceThreadParameters threadParams,
            ulong trampolineAddr);

        [DllImport(Lib, EntryPoint = "nce_signal_interrupt")]
        internal static extern void SignalInterrupt(int coreHandle, ref NceThreadParameters threadParams);

        [DllImport(Lib, EntryPoint = "nce_get_context")]
        private static extern void GetContextNative(int coreHandle, ref NceGuestContextView view);

        [DllImport(Lib, EntryPoint = "nce_set_context")]
        private static extern void SetContextNative(int coreHandle, ref NceGuestContextView view);

        /// <summary>Gets a copy of the core's guest register snapshot.</summary>
        internal static NceGuestContextView? GetContext(int coreHandle)
        {
            NceGuestContextView view = default;
            GetContextNative(coreHandle, ref view);
            return view;
        }

        /// <summary>Sets the core's guest register snapshot (thread must not be running).</summary>
        internal static void SetContext(int coreHandle, NceGuestContextView view)
        {
            SetContextNative(coreHandle, ref view);
        }

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

            fixed (NcePatchResult* pResult = &result)
            {
                // Pass 1: query the required capacity.
                ulong requiredSize = 0;
                int rc = PatchModuleNative(null, 0, imageSize, codeOffset, codeSize,
                    baseVirtualAddr, &requiredSize, pResult);
                if (rc != 0)
                {
                    return null;
                }

                // Allocate a buffer with the required capacity and copy the image in.
                // The native side writes the patched image back into this buffer.
                byte[] buffer = new byte[requiredSize];
                System.Array.Copy(programImage, buffer, programImage.Length);

                // Pass 2: patch.
                ulong finalSize = 0;
                fixed (byte* pBuffer = buffer)
                {
                    rc = PatchModuleNative(pBuffer, requiredSize, imageSize, codeOffset,
                        codeSize, baseVirtualAddr, &finalSize, pResult);
                }

                if (rc != 0 || result.Success == 0)
                {
                    return null;
                }

                // Trim to the actual patched size.
                if (finalSize < (ulong)buffer.LongLength)
                {
                    System.Array.Resize(ref buffer, (int)finalSize);
                }

                return buffer;
            }
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

