using System;
using System.Text;
using Ryujinx.Common.Logging;
using Ryujinx.Cpu;

namespace LibRyubing.Nce
{
    /// <summary>One supervisor call as seen by the NCE run loop.</summary>
    internal readonly struct NceSvcRecord
    {
        public readonly ulong Pc;
        public readonly uint Svc;
        public readonly ulong X0;
        public readonly ulong X1;
        public readonly ulong X2;
        public readonly ulong X3;
        public readonly ulong Result;

        public NceSvcRecord(ulong pc, uint svc, ulong x0, ulong x1, ulong x2, ulong x3, ulong result)
        {
            Pc = pc;
            Svc = svc;
            X0 = x0;
            X1 = x1;
            X2 = x2;
            X3 = x3;
            Result = result;
        }

        public override string ToString() =>
            $"svc=0x{Svc:X} pc=0x{Pc:X} x0=0x{X0:X} x1=0x{X1:X} x2=0x{X2:X} x3=0x{X3:X} r=0x{Result:X}";
    }

    /// <summary>
    /// Fixed-size ring of the most recent SVCs. Pure data structure (unit tested).
    /// </summary>
    internal sealed class NceSvcRing
    {
        private readonly NceSvcRecord[] _records;
        private int _next;
        private int _count;

        public NceSvcRing(int capacity)
        {
            if (capacity <= 0)
            {
                throw new ArgumentOutOfRangeException(nameof(capacity));
            }

            _records = new NceSvcRecord[capacity];
        }

        public int Capacity => _records.Length;
        public int Count => _count;
        public long Total { get; private set; }

        public void Push(in NceSvcRecord record)
        {
            _records[_next] = record;
            _next = (_next + 1) % _records.Length;

            if (_count < _records.Length)
            {
                _count++;
            }

            Total++;
        }

        /// <summary>Returns the retained records, oldest first.</summary>
        public NceSvcRecord[] Snapshot()
        {
            NceSvcRecord[] result = new NceSvcRecord[_count];

            int start = _count < _records.Length ? 0 : _next;

            for (int i = 0; i < _count; i++)
            {
                result[i] = _records[(start + i) % _records.Length];
            }

            return result;
        }
    }

    /// <summary>
    /// Detects a guest spinning on the same SVC with the same key argument
    /// (the QueryMemory storm signature). Pure logic (unit tested).
    /// </summary>
    internal sealed class NceStormDetector
    {
        private uint _lastSvc = uint.MaxValue;
        private ulong _lastKey;
        private int _streak;

        /// <summary>Streak lengths at which a report is requested (then every <see cref="ReportEvery"/>).</summary>
        public const int FirstReport = 64;
        public const int SecondReport = 1024;
        public const int ReportEvery = 8192;

        public int Streak => _streak;

        /// <summary>
        /// Records one SVC. Returns true when the caller should emit a storm report.
        /// </summary>
        /// <param name="svc">SVC number</param>
        /// <param name="key">Argument that identifies "the same" call (e.g. queried address)</param>
        public bool Observe(uint svc, ulong key)
        {
            if (IsArgumentless(svc))
            {
                // X0 is not an input for these, so "same key" is meaningless: nn::os
                // legitimately calls GetCurrentProcessorNumber / GetSystemTick in tight loops.
                Reset();
                return false;
            }

            if (svc == _lastSvc && key == _lastKey)
            {
                _streak++;
            }
            else
            {
                _lastSvc = svc;
                _lastKey = key;
                _streak = 1;
            }

            return _streak == FirstReport
                || _streak == SecondReport
                || (_streak > SecondReport && (_streak % ReportEvery) == 0);
        }

        /// <summary>SVCs whose registers carry no input (never a storm signature).</summary>
        public static bool IsArgumentless(uint svc)
        {
            return svc == 0x10 // GetCurrentProcessorNumber
                || svc == 0x1E // GetSystemTick
                || svc == 0x03; // ExitProcess
        }

        public void Reset()
        {
            _lastSvc = uint.MaxValue;
            _lastKey = 0;
            _streak = 0;
        }
    }

    /// <summary>
    /// Structured NCE logging. Every line starts with <c>NCE|KIND</c> so the smoke triage can
    /// parse it: <c>NCE|SVC</c>, <c>NCE|STORM</c>, <c>NCE|TRACE</c>, <c>NCE|FAULT</c>,
    /// <c>NCE|HALT</c>, <c>NCE|RUN</c>, <c>NCE|BREAK</c>.
    /// Verbosity follows <see cref="NceNative.DebugLevel"/> (0 off, 1 errors, 2 standard, 3 verbose).
    /// </summary>
    internal sealed class NceTrace
    {
        private const int RingCapacity = 256;
        private const int StandardSvcLogBudget = 256;
        private const int TraceDumpCount = 48;

        private readonly NceSvcRing _ring = new(RingCapacity);
        private readonly NceStormDetector _storm = new();
        private int _svcLogRemaining = StandardSvcLogBudget;
        private int _dumpsRemaining = 8;

        public static bool Errors => NceNative.LogErrors;
        public static bool Standard => NceNative.LogStandard;
        public static bool Verbose => NceNative.LogVerbose;

        /// <summary>Emits a run-loop lifecycle line (entry/exit).</summary>
        public static void Run(string what, IExecutionContext ctx)
        {
            if (Standard)
            {
                Logger.Info?.Print(LogClass.Cpu,
                    $"NCE|RUN {what} pc=0x{ctx.Pc:X} sp=0x{ctx.GetX(31):X} tpidr=0x{(ulong)ctx.TpidrEl0:X} tpidrro=0x{(ulong)ctx.TpidrroEl0:X}");
            }
        }

        /// <summary>Emits an unexpected/odd halt line.</summary>
        public static void Halt(string what, ulong hr, IExecutionContext ctx)
        {
            if (Errors)
            {
                Logger.Warning?.Print(LogClass.Cpu, $"NCE|HALT {what} hr=0x{hr:X} pc=0x{ctx.Pc:X} sp=0x{ctx.GetX(31):X}");
            }
        }

        /// <summary>Records a completed SVC (after HLE) and applies storm detection.</summary>
        /// <returns>True when a storm report was emitted (caller may add detail).</returns>
        public bool Svc(in NceSvcRecord record)
        {
            _ring.Push(record);

            if (Standard && _svcLogRemaining > 0)
            {
                _svcLogRemaining--;
                Logger.Info?.Print(LogClass.Cpu, $"NCE|SVC {record} (budget {_svcLogRemaining})");
            }
            else if (Verbose)
            {
                Logger.Info?.Print(LogClass.Cpu, $"NCE|SVC {record}");
            }

            // Key on the pointer-ish argument: QueryMemory(x1 = address), everything else x0.
            ulong key = record.Svc == 0x6 ? record.X1 : record.X0;

            if (_storm.Observe(record.Svc, key))
            {
                if (Errors)
                {
                    Logger.Warning?.Print(LogClass.Cpu,
                        $"NCE|STORM svc=0x{record.Svc:X} key=0x{key:X} streak={_storm.Streak} last: {record}");
                }

                return true;
            }

            return false;
        }

        /// <summary>Dumps the recent SVC history (rate limited) with a reason line.</summary>
        public void DumpRecent(string reason, IExecutionContext ctx)
        {
            if (!Errors || _dumpsRemaining <= 0)
            {
                return;
            }

            _dumpsRemaining--;

            NceSvcRecord[] records = _ring.Snapshot();
            int start = Math.Max(0, records.Length - TraceDumpCount);

            StringBuilder sb = new();
            sb.Append("NCE|TRACE ").Append(reason)
              .Append(" total=").Append(_ring.Total)
              .Append(" showing=").Append(records.Length - start)
              .Append(" pc=0x").Append(ctx.Pc.ToString("X"))
              .Append(" sp=0x").Append(ctx.GetX(31).ToString("X"));

            for (int i = 0; i < 31; i++)
            {
                sb.Append(" x").Append(i).Append("=0x").Append(ctx.GetX(i).ToString("X"));
            }

            Logger.Error?.Print(LogClass.Cpu, sb.ToString());

            for (int i = start; i < records.Length; i++)
            {
                Logger.Error?.Print(LogClass.Cpu, $"NCE|TRACE #{i - start} {records[i]}");
            }
        }

        /// <summary>Emits a guest fault line.</summary>
        public static void Fault(string kind, ulong hr, IExecutionContext ctx)
        {
            if (Errors)
            {
                StringBuilder sb = new();
                sb.Append("NCE|FAULT ").Append(kind)
                  .Append(" hr=0x").Append(hr.ToString("X"))
                  .Append(" pc=0x").Append(ctx.Pc.ToString("X"))
                  .Append(" sp=0x").Append(ctx.GetX(31).ToString("X"));

                for (int i = 0; i < 31; i++)
                {
                    sb.Append(" x").Append(i).Append("=0x").Append(ctx.GetX(i).ToString("X"));
                }

                Logger.Error?.Print(LogClass.Cpu, sb.ToString());
            }
        }
    }
}
