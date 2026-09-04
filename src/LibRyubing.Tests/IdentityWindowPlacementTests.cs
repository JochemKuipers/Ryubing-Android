using Ryujinx.Cpu;
using Xunit;

namespace LibRyubing.Tests
{
    /// <summary>
    /// Eden's ChooseVirtualBase contract: 2 MiB aligned base in [2^36, 2^39 - size).
    /// </summary>
    public class IdentityWindowPlacementTests
    {
        [Theory]
        [InlineData(1UL << 38)]
        [InlineData(1UL << 37)]
        public void PickHint_IsAlignedAndInRange_ForManySeeds(ulong size)
        {
            ulong min = ulong.MaxValue;
            ulong max = 0;

            for (long seed = 0; seed < 100_000; seed += 37)
            {
                ulong hint = IdentityWindowPlacement.PickHint(size, unchecked(seed * 0x1E3779B97F4A7C15L));

                Assert.NotEqual(0UL, hint);
                Assert.Equal(0UL, hint % IdentityWindowPlacement.Alignment);
                Assert.True(hint >= IdentityWindowPlacement.MinimumBase, $"hint 0x{hint:X} below 2^36");
                Assert.True(hint + size <= IdentityWindowPlacement.HostVaLimit, $"hint 0x{hint:X} + size leaves 39-bit space");
                Assert.True(IdentityWindowPlacement.IsValidWindow(hint, size));

                min = System.Math.Min(min, hint);
                max = System.Math.Max(max, hint);
            }

            // The chooser must actually spread across the range, not pin one slot.
            Assert.True(max - min > (IdentityWindowPlacement.HostVaLimit - size - IdentityWindowPlacement.MinimumBase) / 4,
                $"poor spread: min=0x{min:X} max=0x{max:X}");
        }

        [Fact]
        public void PickHint_CoversExtremes()
        {
            ulong size = IdentityWindowPlacement.PreferredSize;
            ulong lowest = IdentityWindowPlacement.PickHint(size, 0);
            Assert.Equal(IdentityWindowPlacement.MinimumBase, lowest);

            // Slot count = (upper - lower + 1); random % range == range - 1 selects the top slot.
            ulong lower = IdentityWindowPlacement.MinimumBase / IdentityWindowPlacement.Alignment;
            ulong upper = (IdentityWindowPlacement.HostVaLimit - size) / IdentityWindowPlacement.Alignment;
            ulong range = upper - lower + 1;
            ulong highest = IdentityWindowPlacement.PickHint(size, (long)(range - 1));
            Assert.Equal(IdentityWindowPlacement.HostVaLimit - size, highest);
        }

        [Fact]
        public void PickHint_RejectsWindowsThatCannotFit()
        {
            Assert.Equal(0UL, IdentityWindowPlacement.PickHint(0, 1));
            Assert.Equal(0UL, IdentityWindowPlacement.PickHint(1UL << 39, 1));
            // 2^39 - 2^36 + 1 byte cannot start at or above 2^36.
            Assert.Equal(0UL, IdentityWindowPlacement.PickHint((1UL << 39) - (1UL << 36) + 1, 1));
        }

        [Fact]
        public void IsValidWindow_EnforcesAlignmentBoundsAndLimit()
        {
            ulong size = IdentityWindowPlacement.PreferredSize;

            Assert.True(IdentityWindowPlacement.IsValidWindow(1UL << 36, size));
            Assert.True(IdentityWindowPlacement.IsValidWindow((1UL << 39) - size, size));

            Assert.False(IdentityWindowPlacement.IsValidWindow((1UL << 36) + 0x1000, size)); // not 2 MiB aligned
            Assert.False(IdentityWindowPlacement.IsValidWindow(1UL << 35, size));            // below 2^36
            Assert.False(IdentityWindowPlacement.IsValidWindow((1UL << 39) - size + 0x200000, size)); // past 2^39
            Assert.False(IdentityWindowPlacement.IsValidWindow(1UL << 36, 0));
            Assert.False(IdentityWindowPlacement.IsValidWindow(0x18B8D8000, size)); // the old overlapping base
        }

        [Fact]
        public void Constants_MatchEden()
        {
            Assert.Equal(1UL << 38, IdentityWindowPlacement.PreferredSize);
            Assert.Equal(0x200000UL, IdentityWindowPlacement.Alignment);
            Assert.Equal(1UL << 36, IdentityWindowPlacement.MinimumBase);
            Assert.Equal(1UL << 39, IdentityWindowPlacement.HostVaLimit);
        }
    }
}
