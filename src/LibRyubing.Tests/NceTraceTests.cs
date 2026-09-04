using LibRyubing.Nce;
using Xunit;

namespace LibRyubing.Tests
{
    public class NceSvcRingTests
    {
        private static NceSvcRecord Rec(uint svc, ulong x0) => new(0x1000 + svc * 4, svc, x0, 0, 0, 0, 0);

        [Fact]
        public void Snapshot_ReturnsOldestFirst_BeforeWrap()
        {
            var ring = new NceSvcRing(4);
            ring.Push(Rec(1, 0));
            ring.Push(Rec(2, 0));

            var snap = ring.Snapshot();
            Assert.Equal(2, snap.Length);
            Assert.Equal(1u, snap[0].Svc);
            Assert.Equal(2u, snap[1].Svc);
            Assert.Equal(2, ring.Count);
            Assert.Equal(2, ring.Total);
        }

        [Fact]
        public void Snapshot_ReturnsOldestFirst_AfterWrap()
        {
            var ring = new NceSvcRing(3);
            for (uint i = 1; i <= 5; i++)
            {
                ring.Push(Rec(i, 0));
            }

            var snap = ring.Snapshot();
            Assert.Equal(new uint[] { 3, 4, 5 }, System.Array.ConvertAll(snap, r => r.Svc));
            Assert.Equal(3, ring.Count);
            Assert.Equal(5, ring.Total);
        }

        [Fact]
        public void Record_FormatsAsStructuredFields()
        {
            var r = new NceSvcRecord(0x1008400010, 0x6, 0xAA, 0x90BC28000, 0, 0, 0);
            string s = r.ToString();
            Assert.Contains("svc=0x6", s);
            Assert.Contains("pc=0x1008400010", s);
            Assert.Contains("x1=0x90BC28000", s);
            Assert.Contains("r=0x0", s);
        }

        [Fact]
        public void Ctor_RejectsNonPositiveCapacity()
        {
            Assert.Throws<System.ArgumentOutOfRangeException>(() => new NceSvcRing(0));
        }
    }

    public class NceStormDetectorTests
    {
        [Fact]
        public void ReportsAt64_1024_ThenEvery8192()
        {
            var d = new NceStormDetector();
            var reports = new System.Collections.Generic.List<int>();

            for (int i = 1; i <= 20_000; i++)
            {
                if (d.Observe(0x6, 0x90BC28000))
                {
                    reports.Add(i);
                }
            }

            Assert.Equal(new[] { 64, 1024, 8192, 16384 }, reports);
            Assert.Equal(20_000, d.Streak);
        }

        [Fact]
        public void DifferentKeyResetsStreak()
        {
            var d = new NceStormDetector();
            for (int i = 0; i < 63; i++)
            {
                Assert.False(d.Observe(0x6, 0x1000));
            }

            Assert.False(d.Observe(0x6, 0x2000)); // walker advanced: not a storm
            Assert.Equal(1, d.Streak);

            for (int i = 0; i < 62; i++)
            {
                Assert.False(d.Observe(0x6, 0x2000));
            }
            Assert.True(d.Observe(0x6, 0x2000)); // 64th identical
        }

        [Fact]
        public void DifferentSvcResetsStreak()
        {
            var d = new NceStormDetector();
            for (int i = 0; i < 63; i++)
            {
                d.Observe(0x6, 0x1000);
            }

            Assert.False(d.Observe(0x1B, 0x1000)); // ArbitrateLock in between
            Assert.Equal(1, d.Streak);
        }

        [Fact]
        public void Reset_ClearsState()
        {
            var d = new NceStormDetector();
            for (int i = 0; i < 100; i++)
            {
                d.Observe(0x6, 0x1000);
            }

            d.Reset();
            Assert.Equal(0, d.Streak);
            Assert.False(d.Observe(0x6, 0x1000));
            Assert.Equal(1, d.Streak);
        }

        [Fact]
        public void ArgumentlessSvcsNeverStorm()
        {
            // Violet's nn::os calls GetCurrentProcessorNumber (0x10) thousands of times in a
            // row with whatever happens to be in X0; that is not a storm.
            var d = new NceStormDetector();
            for (int i = 0; i < 5000; i++)
            {
                Assert.False(d.Observe(0x10, 0x12B9EB50F8));
                Assert.False(d.Observe(0x1E, 0x12B9116000));
            }

            Assert.Equal(0, d.Streak);
            Assert.True(NceStormDetector.IsArgumentless(0x10));
            Assert.False(NceStormDetector.IsArgumentless(0x06)); // QueryMemory
        }
    }
}
