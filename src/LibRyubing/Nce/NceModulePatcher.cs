using System;
using Ryujinx.HLE;

namespace LibRyubing.Nce
{
    /// <summary>
    /// Public facade over <see cref="NceNative"/> for HLE loaders (injected via
    /// <see cref="HleConfiguration.NceModulePatcher"/>).
    /// </summary>
    public sealed class NceModulePatcher : INceModulePatcher
    {
        public void ClearTrampolines()
        {
            NceNative.ClearTrampolines();
        }

        public ulong EstimatePatchGrowth(ulong imageSize, ulong codeOffset, ulong codeSize)
        {
            if (!NceNative.TryQueryPatchCapacity(imageSize, codeOffset, codeSize, out ulong required))
            {
                // Conservative fallback matching native PatchGrowthEstimate.
                required = imageSize + (imageSize / 2) + (64 * 1024);
            }

            if (required <= imageSize)
            {
                return 0;
            }

            return AlignUp(required - imageSize, 0x1000);
        }

        public bool TryPatchModule(
            ReadOnlySpan<byte> image,
            ulong codeOffset,
            ulong codeSize,
            ulong baseVirtualAddress,
            out byte[] patchedImage,
            out NceModulePatchLayout layout)
        {
            patchedImage = null;
            layout = default;

            if (image.IsEmpty)
            {
                return false;
            }

            byte[] input = image.ToArray();
            byte[] patched = NceNative.PatchModule(input, codeOffset, codeSize, baseVirtualAddress, out var result);
            if (patched is null || result.Success == 0)
            {
                return false;
            }

            patchedImage = patched;
            layout = new NceModulePatchLayout
            {
                PatchedImageSize = result.PatchedImageSize,
                PatchOffset = result.PatchOffset,
                PatchSize = result.PatchSize,
                PrePatchOffset = result.PrePatchOffset,
                PrePatchSize = result.PrePatchSize,
                PatchMode = result.PatchMode,
            };

            return true;
        }

        private static ulong AlignUp(ulong value, ulong alignment)
        {
            return (value + (alignment - 1)) & ~(alignment - 1);
        }
    }
}
