using System;
using ARMeilleure.State;
using Ryujinx.Cpu;

namespace LibRyubing.Nce
{
    /// <summary>
    /// NCE execution context: holds the guest register state for one thread.
    ///
    /// Phase 0 stub: the register accessors are backed by a plain managed
    /// array so the shape is right; later phases swap this for a view over
    /// the native NceGuestContext (a pinned buffer shared with the native
    /// signal handlers — the managed side must never touch it while the
    /// guest thread is running, which the ICpuContext.Execute contract
    /// already guarantees).
    /// </summary>
    internal sealed class NceExecutionContext : IExecutionContext
    {
        private readonly ulong[] _x = new ulong[32];
        private readonly V128[] _v = new V128[32];

        private ulong _pc;
        private long _tpidrEl0;
        private long _tpidrroEl0;
        private uint _pstate;
        private uint _fpcr;
        private uint _fpsr;
        private bool _running;

        internal ExceptionCallbacks Callbacks { get; }
        internal volatile bool StopRequested;

        public NceExecutionContext(ExceptionCallbacks callbacks)
        {
            Callbacks = callbacks;
        }

        /// <inheritdoc/>
        public ulong Pc => _pc;

        /// <inheritdoc/>
        public long TpidrEl0
        {
            get => _tpidrEl0;
            set => _tpidrEl0 = value;
        }

        /// <inheritdoc/>
        public long TpidrroEl0
        {
            get => _tpidrroEl0;
            set => _tpidrroEl0 = value;
        }

        /// <inheritdoc/>
        public uint Pstate
        {
            get => _pstate;
            set => _pstate = value;
        }

        /// <inheritdoc/>
        public uint Fpcr
        {
            get => _fpcr;
            set => _fpcr = value;
        }

        /// <inheritdoc/>
        public uint Fpsr
        {
            get => _fpsr;
            set => _fpsr = value;
        }

        /// <inheritdoc/>
        public bool IsAarch32
        {
            get => false; // NCE is AArch64-only; a 32-bit title never gets an NCE context
            set => throw new NotSupportedException("NCE does not support AArch32");
        }

        /// <inheritdoc/>
        public ulong ThreadUid { get; set; }

        /// <inheritdoc/>
        public bool Running => _running;

        /// <inheritdoc/>
        public ulong GetX(int index)
        {
            return _x[index];
        }

        /// <inheritdoc/>
        public void SetX(int index, ulong value)
        {
            _x[index] = value;
        }

        /// <inheritdoc/>
        public V128 GetV(int index)
        {
            return _v[index];
        }

        /// <inheritdoc/>
        public void SetV(int index, V128 value)
        {
            _v[index] = value;
        }

        /// <inheritdoc/>
        public void RequestInterrupt()
        {
            // Phase 3: signals the running guest thread via SIGURG
            // (esr_el1 |= BreakLoop, then tkill). The guest exits to the
            // managed scheduler, which invokes Callbacks.InterruptCallback.
            Callbacks.InterruptCallback?.Invoke(this);
        }

        /// <inheritdoc/>
        public void StopRunning()
        {
            // Phase 3: same as RequestInterrupt but with a "don't resume" flag;
            // the native run loop returns and Execute() unwinds.
            StopRequested = true;
            _running = false;
        }

        /// <inheritdoc/>
        public void RequestDebugStep()
        {
            // NCE runs guest code natively; single-stepping requires decoding
            // and re-executing one instruction through the interpreter
            // fallback. Deferred to a later phase (see plan, phase 6).
            throw new NotSupportedException("NCE single-stepping is not implemented yet");
        }

        /// <inheritdoc/>
        public ulong DebugPc
        {
            get => _pc;
            set => _pc = value;
        }

        /// <summary>Marks the context as inside the native run loop (phase 3).</summary>
        internal void SetRunning(bool running)
        {
            _running = running;
        }

        /// <summary>Sets the PC directly; used by Execute before entering the run loop.</summary>
        internal void SetPc(ulong pc)
        {
            _pc = pc;
        }

        public void Dispose()
        {
            // Nothing to dispose in the stub; native thread parameters are
            // released by the CPU context (phase 3).
        }
    }
}
