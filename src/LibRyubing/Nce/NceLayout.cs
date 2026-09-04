using System;
using System.Collections.Generic;

namespace LibRyubing.Nce
{
    /// <summary>Half-open address range.</summary>
    internal readonly record struct NceRegion(string Name, ulong Start, ulong End)
    {
        public ulong Size => End - Start;
        public bool IsEmpty => End <= Start;
        public bool Contains(ulong address) => address >= Start && address < End;
        public bool Within(ulong start, ulong end) => IsEmpty || (Start >= start && End <= end && End >= Start);
        public bool Overlaps(NceRegion other) => !IsEmpty && !other.IsEmpty && Start < other.End && other.Start < End;

        public override string ToString() => $"{Name}=[0x{Start:X},0x{End:X})";
    }

    /// <summary>
    /// Pure model of the kernel's identity-mapped 39-bit process layout
    /// (<c>KPageTableBase.CreateUserAddressSpace</c>, patch 0012). Kept in sync by
    /// <see cref="TryParseLayoutLine"/> tests against the real <c>NCE|LAYOUT</c> log line.
    /// Lets us assert offline that a given window can hold the layout Violet needs
    /// (12 GiB heap, alias extra) before spending a device run on it.
    /// </summary>
    internal static class NceLayout
    {
        /// <summary>Loader places the first NSO at window + 0x8000000 + <see cref="CodeStartOffset"/>.</summary>
        public const ulong CodeOffsetInWindow = 0x8000000;
        public const ulong CodeStartOffset = 0x500000;
        public const ulong RegionAlignment = 0x200000;
        public const ulong FullLayoutMinimumWindow = 0x4000000000; // 256 GiB

        public const ulong FullAliasSize = 0x1000000000;
        public const ulong FullStackSize = 0x80000000;
        public const ulong FullTlsIoSize = 0x1000000000;
        public const ulong Heap12GiB = 0x300000000;
        public const ulong Heap6GiB = 0x180000000;

        /// <param name="AddressSpace">Kernel address space, always [0, 2^39) like real hardware</param>
        /// <param name="Window">Identity window (host reservation); every other region lies inside it</param>
        public sealed record Result(
            NceRegion AddressSpace,
            NceRegion Window,
            NceRegion Code,
            NceRegion Aslr,
            NceRegion Alias,
            NceRegion Heap,
            NceRegion Stack,
            NceRegion TlsIo,
            ulong AliasExtraSize)
        {
            public IEnumerable<NceRegion> Regions
            {
                get
                {
                    yield return Code;
                    yield return Alias;
                    yield return Heap;
                    yield return Stack;
                    yield return TlsIo;
                }
            }
        }

        /// <summary>
        /// Computes the packed (ASLR offset 0) layout the kernel would produce.
        /// </summary>
        /// <param name="windowBase">Identity window base</param>
        /// <param name="windowSize">Identity window size</param>
        /// <param name="codeSize">Total code size (all NSOs incl. patch growth)</param>
        /// <param name="heap12GiB">MemoryConfiguration 12 GiB</param>
        /// <param name="aliasExtra">ProcessCreationFlags.EnableAliasRegionExtraSize</param>
        /// <param name="result">Layout on success</param>
        /// <returns>False if the regions do not fit in the window</returns>
        public static bool TryCompute(ulong windowBase, ulong windowSize, ulong codeSize, bool heap12GiB, bool aliasExtra, out Result result)
        {
            result = null;

            ulong windowEnd = Math.Min(windowBase + windowSize, 1UL << 39);
            ulong effectiveSize = windowEnd - windowBase;

            ulong alias, stack, tlsIo;
            ulong heap = heap12GiB ? Heap12GiB : Heap6GiB;

            if (effectiveSize >= FullLayoutMinimumWindow)
            {
                alias = FullAliasSize;
                stack = FullStackSize;
                tlsIo = FullTlsIoSize;
            }
            else
            {
                int width = (int)ulong.Log2(effectiveSize);
                alias = 1UL << (width - 3);
                stack = 1UL << (width - 8);
                tlsIo = 1UL << (width - 3);
            }

            // Real kernel: 1/8 of the 39-bit space (64 GiB). Shrunken window: 1/8 of the window.
            ulong aliasExtraSize = 0;
            if (aliasExtra)
            {
                aliasExtraSize = effectiveSize >= FullLayoutMinimumWindow ? (1UL << 39) / 8 : effectiveSize / 8;
            }
            alias += aliasExtraSize;

            ulong loadAddress = windowBase + CodeOffsetInWindow + CodeStartOffset;
            ulong codeStart = AlignDown(loadAddress, RegionAlignment);
            ulong codeEnd = AlignUp(loadAddress + codeSize, RegionAlignment);

            ulong cursor = codeEnd;
            NceRegion aliasRegion = new("alias", cursor, cursor + alias);
            cursor += alias;
            NceRegion heapRegion = new("heap", cursor, cursor + heap);
            cursor += heap;
            NceRegion stackRegion = new("stack", cursor, cursor + stack);
            cursor += stack;
            NceRegion tlsIoRegion = new("tlsio", cursor, cursor + tlsIo);
            cursor += tlsIo;

            if (cursor > windowEnd || cursor < codeEnd)
            {
                return false;
            }

            result = new Result(
                new NceRegion("as", 0, 1UL << 39),
                new NceRegion("window", windowBase, windowEnd),
                new NceRegion("code", codeStart, codeEnd),
                new NceRegion("aslr", windowBase + CodeOffsetInWindow, windowEnd),
                aliasRegion,
                heapRegion,
                stackRegion,
                tlsIoRegion,
                aliasExtraSize);

            return true;
        }

        /// <summary>
        /// Validates a layout (computed or parsed from a log line): address space is the full
        /// [0, 2^39) (rtld's QueryMemory walk starts at 0), the window lies inside it, every
        /// region is inside the window, no overlaps, ASLR region starts at window + 0x8000000.
        /// </summary>
        public static bool Validate(Result layout, out List<string> problems)
        {
            problems = new List<string>();

            if (layout.AddressSpace.Start != 0 || layout.AddressSpace.End != (1UL << 39))
            {
                problems.Add($"address space {layout.AddressSpace} is not [0x0,0x{1UL << 39:X}); QueryMemory(0) walk would terminate early");
            }

            if (!layout.Window.Within(layout.AddressSpace.Start, layout.AddressSpace.End) || layout.Window.IsEmpty)
            {
                problems.Add($"{layout.Window} outside {layout.AddressSpace}");
            }

            if (layout.Aslr.Start != layout.Window.Start + CodeOffsetInWindow)
            {
                problems.Add($"aslr start 0x{layout.Aslr.Start:X} != window + 0x{CodeOffsetInWindow:X}");
            }

            if (layout.Aslr.End > layout.Window.End)
            {
                problems.Add($"{layout.Aslr} ends past {layout.Window}");
            }

            if (layout.Code.Start < layout.Aslr.Start || layout.Code.End > layout.Aslr.End)
            {
                problems.Add($"{layout.Code} outside {layout.Aslr}");
            }

            List<NceRegion> regions = new(layout.Regions);

            foreach (NceRegion region in regions)
            {
                if (!region.Within(layout.Window.Start, layout.Window.End))
                {
                    problems.Add($"{region} outside {layout.Window}");
                }
            }

            for (int i = 0; i < regions.Count; i++)
            {
                for (int j = i + 1; j < regions.Count; j++)
                {
                    if (regions[i].Overlaps(regions[j]))
                    {
                        problems.Add($"{regions[i]} overlaps {regions[j]}");
                    }
                }
            }

            return problems.Count == 0;
        }

        /// <summary>
        /// Parses the kernel's <c>NCE|LAYOUT as=[..) window=[..) code=[..) aslr=[..) alias=[..) heap=[..) stack=[..) tlsio=[..)</c>
        /// line. Returns false if the line is not a layout line or is malformed.
        /// </summary>
        public static bool TryParseLayoutLine(string line, out Result result)
        {
            result = null;

            int idx = line.IndexOf("NCE|LAYOUT", StringComparison.Ordinal);
            if (idx < 0)
            {
                return false;
            }

            Dictionary<string, NceRegion> regions = new();
            string rest = line[(idx + "NCE|LAYOUT".Length)..];

            foreach (string token in rest.Split(' ', StringSplitOptions.RemoveEmptyEntries))
            {
                int eq = token.IndexOf('=');
                if (eq <= 0 || eq + 1 >= token.Length || token[eq + 1] != '[')
                {
                    continue;
                }

                string name = token[..eq];
                string range = token[(eq + 2)..];
                int close = range.IndexOf(')');
                if (close < 0)
                {
                    return false;
                }

                string[] parts = range[..close].Split(',');
                if (parts.Length != 2 || !TryParseHex(parts[0], out ulong start) || !TryParseHex(parts[1], out ulong end))
                {
                    return false;
                }

                regions[name] = new NceRegion(name, start, end);
            }

            string[] required = { "as", "window", "code", "aslr", "alias", "heap", "stack", "tlsio" };
            foreach (string name in required)
            {
                if (!regions.ContainsKey(name))
                {
                    return false;
                }
            }

            result = new Result(
                regions["as"], regions["window"], regions["code"], regions["aslr"], regions["alias"],
                regions["heap"], regions["stack"], regions["tlsio"], 0);

            return true;
        }

        private static bool TryParseHex(string text, out ulong value)
        {
            text = text.Trim();
            if (text.StartsWith("0x", StringComparison.OrdinalIgnoreCase))
            {
                text = text[2..];
            }

            return ulong.TryParse(text, System.Globalization.NumberStyles.HexNumber, null, out value);
        }

        private static ulong AlignUp(ulong value, ulong alignment) => (value + alignment - 1) & ~(alignment - 1);
        private static ulong AlignDown(ulong value, ulong alignment) => value & ~(alignment - 1);
    }
}
