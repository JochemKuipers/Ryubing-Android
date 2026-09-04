using Ryujinx.Cpu;
using Ryujinx.Cpu.Jit;
using Ryujinx.Memory;
using System;
using System.Linq;
using System.Runtime.InteropServices;
using Xunit;

namespace LibRyubing.Tests
{
    /// <summary>
    /// Exercises <see cref="MemoryManagerHostNoMirror"/> in identity mode on the host with a
    /// real reserved window and a real shared backing block: guest VA must be the host pointer,
    /// HLE-side reads/writes must land on the same bytes natively executing code would see, and
    /// scattered physical pages must be stitched correctly across page boundaries.
    /// </summary>
    public sealed class IdentityMemoryManagerTests : IDisposable
    {
        private const ulong PageSize = 0x1000;
        private const ulong BackingSize = 16UL << 20;

        private readonly MemoryBlock _backing;
        private readonly MemoryBlock _window;
        private readonly MemoryManagerHostNoMirror _mm;

        public IdentityMemoryManagerTests()
        {
            _backing = new MemoryBlock(BackingSize, MemoryAllocationFlags.Reserve | MemoryAllocationFlags.Mirrorable);
            _backing.Commit(0, BackingSize);

            if (!AddressSpace.TryCreateIdentityWindow(1UL << 30, 1UL << 28, out _window))
            {
                throw new InvalidOperationException("could not reserve an identity window on the host");
            }

            _mm = new MemoryManagerHostNoMirror(_window, _backing, unsafeMode: false, invalidAccessHandler: null, identityMapped: true);
            _mm.IncrementReferenceCount();
        }

        public void Dispose()
        {
            _mm.DecrementReferenceCount(); // Destroy(): releases the window reservation
            _backing.Dispose();
        }

        [Fact]
        public void WindowIsIdentityAndInsideHostRange()
        {
            Assert.True(_mm.IsIdentityMapped);
            Assert.Equal((ulong)_window.Pointer, _mm.WindowBase);
            Assert.Equal(_mm.WindowBase + _window.Size, _mm.WindowEnd);
            Assert.True(IdentityWindowPlacement.IsValidWindow(_mm.WindowBase, _window.Size));
            Assert.Equal(nint.Zero, _mm.PageTablePointer);
        }

        [Fact]
        public void UnmappedWindowAddressesAreNotMappedAndDoNotThrow()
        {
            Assert.False(_mm.IsMapped(_mm.WindowBase));
            Assert.False(_mm.IsRangeMapped(_mm.WindowBase, PageSize));
            // Inside the kernel's 39-bit space but outside the window: not mapped, not an error.
            Assert.False(_mm.IsRangeMapped(0x8000000, PageSize));
            Assert.False(_mm.IsMapped(0));
        }

        [Fact]
        public unsafe void HleWriteIsVisibleThroughTheGuestPointerAcrossScatteredPages()
        {
            ulong va = _mm.WindowBase + 0x10_0000;

            // Four VA pages backed by non-contiguous physical pages, in a deliberately shuffled order.
            ulong[] pas = { 0x30000, 0x10000, 0x50000, 0x00000 };
            for (int i = 0; i < pas.Length; i++)
            {
                _mm.Map(va + (ulong)i * PageSize, pas[i], PageSize, MemoryMapFlags.None);
            }

            Assert.True(_mm.IsRangeMapped(va, 4 * PageSize));
            Assert.False(_mm.IsRangeMapped(va, 5 * PageSize));

            // An HLE write that starts mid-page and spans three page boundaries.
            byte[] payload = Enumerable.Range(0, (int)(3 * PageSize)).Select(i => (byte)(i * 7 + 3)).ToArray();
            ulong writeVa = va + 100;
            _mm.Write(writeVa, payload);

            // Natively executing guest code would dereference the VA directly.
            byte* guest = (byte*)writeVa;
            for (int i = 0; i < payload.Length; i++)
            {
                if (guest[i] != payload[i])
                {
                    Assert.Fail($"byte {i} at guest 0x{writeVa + (ulong)i:X}: 0x{guest[i]:X2} != 0x{payload[i]:X2}");
                }
            }

            // ...and the bytes sit on the intended physical pages.
            Span<byte> firstPage = _backing.GetSpan(pas[0] + 100, (int)(PageSize - 100));
            Assert.True(firstPage.SequenceEqual(payload.AsSpan(0, (int)(PageSize - 100))));
            Span<byte> secondPage = _backing.GetSpan(pas[1], (int)PageSize);
            Assert.True(secondPage.SequenceEqual(payload.AsSpan((int)(PageSize - 100), (int)PageSize)));

            // Guest store -> HLE read.
            guest[5] = 0xAB;
            Assert.Equal(0xAB, _mm.Read<byte>(writeVa + 5));

            // Host regions point into the backing block, one per physical run.
            var regions = _mm.GetHostRegions(va, 4 * PageSize).ToArray();
            Assert.Equal(4, regions.Length);
            Assert.Equal((nuint)((ulong)_backing.Pointer + pas[0]), regions[0].Address);

            _mm.Unmap(va, 4 * PageSize);
            Assert.False(_mm.IsRangeMapped(va, PageSize));
        }

        [Fact]
        public unsafe void ReadWriteOfLargeSpanMatchesGuestView()
        {
            ulong va = _mm.WindowBase + 0x20_0000;
            const int pages = 64;
            for (int i = 0; i < pages; i++)
            {
                // Reverse physical order to make every page boundary a discontinuity.
                _mm.Map(va + (ulong)i * PageSize, 0x80000 + (ulong)(pages - 1 - i) * PageSize, PageSize, MemoryMapFlags.None);
            }

            byte[] data = new byte[pages * (int)PageSize - 4096 - 17];
            new Random(1234).NextBytes(data);
            _mm.Write(va + 17, data);

            byte[] back = new byte[data.Length];
            _mm.Read(va + 17, back);
            Assert.True(back.AsSpan().SequenceEqual(data));

            ReadOnlySpan<byte> direct = new((byte*)(va + 17), data.Length);
            Assert.True(direct.SequenceEqual(data));

            _mm.Unmap(va, pages * PageSize);
        }
    }
}
