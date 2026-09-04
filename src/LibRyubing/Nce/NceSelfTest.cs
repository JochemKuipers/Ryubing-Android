using System;
using System.Runtime.InteropServices;
using Ryujinx.Common.Logging;

namespace LibRyubing.Nce
{
    /// <summary>
    /// Runs the native NCE self-test (see <c>nce_self_test</c> in nce.cpp) and logs a
    /// structured <c>NCE|SELFTEST</c> verdict. The test executes a tiny guest snippet through
    /// the real run loop inside the reserved identity window and exercises: plain execution,
    /// load/store, the SVC trampoline round-trip (register fidelity), the alignment-fault
    /// interpreter path and the access-fault (DataAbort) path. It needs no game and catches
    /// regressions in the trampolines, context save/restore and both signal handlers.
    /// </summary>
    internal static class NceSelfTest
    {
        /// <summary>Stage bits reported by the native side (must match NceSelfTestStage in nce.h).</summary>
        [Flags]
        internal enum Stage : uint
        {
            None = 0,
            Setup = 1 << 0,
            Execute = 1 << 1,
            LoadStore = 1 << 2,
            Svc = 1 << 3,
            SvcRegisters = 1 << 4,
            Alignment = 1 << 5,
            DataAbort = 1 << 6,
            Cleanup = 1 << 7,
            Interrupt = 1 << 8,
        }

        /// <summary>Runs the self-test when the NCE debug level is at least "standard".</summary>
        /// <param name="settings">Emulator settings (debug level gate)</param>
        /// <param name="windowBase">Base of the reserved identity window</param>
        /// <param name="windowSize">Size of the reserved identity window</param>
        internal static void RunIfEnabled(EmulatorSettings settings, ulong windowBase, ulong windowSize)
        {
            if (settings.NceDebugLevel < 2)
            {
                return;
            }

            Run(windowBase, windowSize);
        }

        /// <summary>
        /// Runs the self-test inside the given identity window and logs the verdict.
        /// The native side borrows the top of the window as scratch and restores it to PROT_NONE.
        /// </summary>
        /// <returns>True when every stage passed</returns>
        internal static bool Run(ulong windowBase, ulong windowSize)
        {
            NceNative.NceSelfTestResult result = default;

            try
            {
                int rc = NceNative.SelfTest(windowBase, windowSize, ref result);

                Stage ran = (Stage)result.StagesRun;
                Stage failed = (Stage)result.StagesFailed;

                string detail =
                    $"ran=[{ran}] failed=[{failed}] svcNumber=0x{result.ObservedSvcNumber:X} " +
                    $"svcX0=0x{result.ObservedSvcX0:X} storeValue=0x{result.ObservedStoreValue:X} " +
                    $"alignValue=0x{result.ObservedAlignmentValue:X} haltReason=0x{result.ObservedHaltReason:X} " +
                    $"faultPc=0x{result.ObservedFaultPc:X} scratch=0x{result.ScratchAddress:X}";

                if (rc == 0 && failed == Stage.None)
                {
                    Logger.Info?.Print(LogClass.Cpu, $"NCE|SELFTEST PASS {detail}");
                    return true;
                }

                Logger.Error?.Print(LogClass.Cpu, $"NCE|SELFTEST FAIL rc={rc} {detail}");
                return false;
            }
            catch (EntryPointNotFoundException)
            {
                Logger.Warning?.Print(LogClass.Cpu, "NCE|SELFTEST SKIP native library has no nce_self_test");
                return false;
            }
            catch (Exception ex)
            {
                Logger.Error?.Print(LogClass.Cpu, $"NCE|SELFTEST FAIL exception={ex.GetType().Name}: {ex.Message}");
                return false;
            }
        }
    }
}
