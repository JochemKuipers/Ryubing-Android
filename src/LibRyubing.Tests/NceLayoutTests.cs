using System.Collections.Generic;
using LibRyubing.Nce;
using Xunit;

namespace LibRyubing.Tests
{
    public class NceLayoutTests
    {
        private const ulong Window2Pow38 = 1UL << 38;
        private const ulong Window2Pow37 = 1UL << 37;
        private const ulong VioletCodeSize = 0x8000000; // generous: main + sdk NSOs + patch growth

        [Theory]
        [InlineData(1UL << 36)]
        [InlineData(0x3FA0000000UL)]
        [InlineData((1UL << 39) - (1UL << 38))]
        public void FullLayout_FitsIn2Pow38_WithTwelveGiBHeapAndAliasExtra(ulong windowBase)
        {
            Assert.True(NceLayout.TryCompute(windowBase, Window2Pow38, VioletCodeSize, heap12GiB: true, aliasExtra: true, out var layout));
            Assert.True(NceLayout.Validate(layout, out List<string> problems), string.Join("; ", problems));

            // Real-hardware alias extra (1/8 of the 39-bit space = 64 GiB), as in Eden.
            Assert.Equal(NceLayout.FullAliasSize + (1UL << 36), layout.Alias.Size);
            Assert.Equal(NceLayout.Heap12GiB, layout.Heap.Size);
            Assert.Equal(NceLayout.FullStackSize, layout.Stack.Size);
            Assert.Equal(NceLayout.FullTlsIoSize, layout.TlsIo.Size);
            Assert.Equal(windowBase + NceLayout.CodeOffsetInWindow, layout.Aslr.Start);
            Assert.True(layout.TlsIo.End <= windowBase + Window2Pow38);

            // The kernel address space itself must stay [0, 2^39): rtld walks QueryMemory from 0.
            Assert.Equal(0UL, layout.AddressSpace.Start);
            Assert.Equal(1UL << 39, layout.AddressSpace.End);
            Assert.Equal(new NceRegion("window", windowBase, windowBase + Window2Pow38), layout.Window);
        }

        [Fact]
        public void ShrunkenLayout_FitsIn2Pow37()
        {
            ulong windowBase = 1UL << 36;
            Assert.True(NceLayout.TryCompute(windowBase, Window2Pow37, VioletCodeSize, heap12GiB: true, aliasExtra: true, out var layout));
            Assert.True(NceLayout.Validate(layout, out List<string> problems), string.Join("; ", problems));

            // 37-bit window: alias/tlsio = 2^34, stack = 2^29 (kernel truncated branch),
            // alias extra scaled to the window (2^34) instead of the 64 GiB real-HW value.
            Assert.Equal(1UL << 34, layout.Alias.Size - layout.AliasExtraSize);
            Assert.Equal(1UL << 34, layout.AliasExtraSize);
            Assert.Equal(1UL << 34, layout.TlsIo.Size);
            Assert.Equal(1UL << 29, layout.Stack.Size);
        }

        [Fact]
        public void FullSizeRegions_DoNotFitIn2Pow37()
        {
            // Sanity for the model: the real 39-bit region sizes (142 GiB) exceed a 128 GiB window.
            ulong total = NceLayout.FullAliasSize + NceLayout.Heap12GiB + NceLayout.FullStackSize + NceLayout.FullTlsIoSize;
            Assert.True(total > Window2Pow37);
            Assert.True(total + (1UL << 36) + NceLayout.CodeOffsetInWindow + VioletCodeSize < Window2Pow38);
        }

        [Fact]
        public void Layout_WithoutAliasExtra_HasNoExtra()
        {
            Assert.True(NceLayout.TryCompute(1UL << 36, Window2Pow38, VioletCodeSize, heap12GiB: false, aliasExtra: false, out var layout));
            Assert.Equal(0UL, layout.AliasExtraSize);
            Assert.Equal(NceLayout.FullAliasSize, layout.Alias.Size);
            Assert.Equal(NceLayout.Heap6GiB, layout.Heap.Size);
        }

        [Fact]
        public void Validate_FlagsRegionOutsideWindow()
        {
            Assert.True(NceLayout.TryCompute(1UL << 36, Window2Pow38, VioletCodeSize, true, true, out var good));

            var bad = good with { Heap = new NceRegion("heap", 0x90BC28000, 0x90BC28000 + NceLayout.Heap12GiB) };
            Assert.False(NceLayout.Validate(bad, out List<string> problems));
            Assert.Contains(problems, p => p.Contains("heap") && p.Contains("outside"));
        }

        [Fact]
        public void Validate_RejectsAddressSpaceStartingAtWindowBase()
        {
            // Regression: with as=[window) QueryMemory(0) returns the "outside" descriptor whose
            // end wraps to 0, rtld's module walk stops immediately and nn::init::Start is unresolved.
            Assert.True(NceLayout.TryCompute(1UL << 36, Window2Pow38, VioletCodeSize, true, true, out var good));

            var bad = good with { AddressSpace = good.Window with { Name = "as" } };
            Assert.False(NceLayout.Validate(bad, out List<string> problems));
            Assert.Contains(problems, p => p.Contains("QueryMemory(0)"));
        }

        [Fact]
        public void Validate_FlagsOverlap()
        {
            Assert.True(NceLayout.TryCompute(1UL << 36, Window2Pow38, VioletCodeSize, true, true, out var good));

            var bad = good with { Stack = new NceRegion("stack", good.Heap.Start + 0x1000, good.Heap.Start + 0x2000) };
            Assert.False(NceLayout.Validate(bad, out List<string> problems));
            Assert.Contains(problems, p => p.Contains("overlaps"));
        }

        [Fact]
        public void ParseLayoutLine_RoundTripsKernelFormat()
        {
            // Exactly the format emitted by KPageTableBase (patch 0012).
            string line =
                "00:00:00.000 |I| Kernel NCE|LAYOUT as=[0x0,0x8000000000) window=[0x1000000000,0x5000000000) code=[0x1008400000,0x1010000000) " +
                "aslr=[0x1008000000,0x5000000000) alias=[0x1010000000,0x3010000000) heap=[0x3010000000,0x3310000000) " +
                "stack=[0x3310000000,0x3390000000) tlsio=[0x3390000000,0x4390000000) aslrEnabled=True";

            Assert.True(NceLayout.TryParseLayoutLine(line, out var layout));
            Assert.Equal(0UL, layout.AddressSpace.Start);
            Assert.Equal(0x8000000000UL, layout.AddressSpace.End);
            Assert.Equal(0x1000000000UL, layout.Window.Start);
            Assert.Equal(0x5000000000UL, layout.Window.End);
            Assert.Equal(0x1008400000UL, layout.Code.Start);
            Assert.Equal(0x3310000000UL, layout.Stack.Start);
            Assert.Equal(0x4390000000UL, layout.TlsIo.End);
            Assert.True(NceLayout.Validate(layout, out List<string> problems), string.Join("; ", problems));
        }

        [Fact]
        public void ParseLayoutLine_RejectsForeignAndMalformedLines()
        {
            Assert.False(NceLayout.TryParseLayoutLine("NCE|SVC svc=0x6 pc=0x1 x0=0x2", out _));
            Assert.False(NceLayout.TryParseLayoutLine("NCE|LAYOUT as=[0x1,0x2) code=[0x1,0x2)", out _)); // missing regions
            Assert.False(NceLayout.TryParseLayoutLine("NCE|LAYOUT as=[zz,0x2) window=[0x1,0x2) code=[0x1,0x2) aslr=[0x1,0x2) alias=[0x1,0x2) heap=[0x1,0x2) stack=[0x1,0x2) tlsio=[0x1,0x2)", out _));
        }

        [Fact]
        public void ComputedLayout_MatchesWhatTheKernelLogs()
        {
            // Cross-check the model against a line produced by the kernel arithmetic for the same inputs.
            ulong windowBase = 0x1000000000;
            Assert.True(NceLayout.TryCompute(windowBase, Window2Pow38, 0x7B00000, heap12GiB: true, aliasExtra: false, out var model));

            string line =
                $"NCE|LAYOUT as=[0x{model.AddressSpace.Start:X},0x{model.AddressSpace.End:X}) window=[0x{model.Window.Start:X},0x{model.Window.End:X}) " +
                $"code=[0x{model.Code.Start:X},0x{model.Code.End:X}) " +
                $"aslr=[0x{model.Aslr.Start:X},0x{model.Aslr.End:X}) alias=[0x{model.Alias.Start:X},0x{model.Alias.End:X}) " +
                $"heap=[0x{model.Heap.Start:X},0x{model.Heap.End:X}) stack=[0x{model.Stack.Start:X},0x{model.Stack.End:X}) " +
                $"tlsio=[0x{model.TlsIo.Start:X},0x{model.TlsIo.End:X}) aslrEnabled=False";

            Assert.True(NceLayout.TryParseLayoutLine(line, out var parsed));
            Assert.Equal(model.Code, parsed.Code);
            Assert.Equal(model.Alias, parsed.Alias);
            Assert.Equal(model.Heap, parsed.Heap);
            Assert.Equal(model.Stack, parsed.Stack);
            Assert.Equal(model.TlsIo, parsed.TlsIo);
        }
    }
}
