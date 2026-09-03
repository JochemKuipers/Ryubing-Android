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

