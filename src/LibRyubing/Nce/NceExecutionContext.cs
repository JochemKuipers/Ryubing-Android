using System;
using ARMeilleure.State;
using Ryujinx.Cpu;

namespace LibRyubing.Nce
{
    /// <summary>
    /// NCE execution context: holds the guest register state for one thread
    /// and owns the native NCE core (lazily created on first Execute).
    ///
    /// Register accessors operate on a cached snapshot that is synced
    /// to/from the native GuestContext at Execute-loop boundaries (only
    /// safe while the guest is stopped, which the ICpuContext.Execute
    /// contract guarantees). Vector registers are managed-side storage
    /// until the native view struct grows a VPR field.
    /// </summary>
    internal sealed class NceExecutionContext : IExecutionContext
    {
        // Cached native context view (synced at loop boundaries).
        private NceNative.NceGuestContextView _view;
        private readonly V128[] _v = new V128[32];

        // Native core handle; created lazily inside Execute() so that
        // gettid()/sigaltstack() bind to the thread that runs the guest.
        private int _coreHandle = -1;

        private volatile bool _running;

        internal ExceptionCallbacks Callbacks { get; }
        internal volatile bool StopRequested;
        internal int CoreHandle => _coreHandle;

        public NceExecutionContext(ExceptionCallbacks callbacks)
        {
            Callbacks = callbacks;
        }

        /// <summary>
        /// Creates the native core from the calling thread. Must be called
        /// from the thread that will run guest code (the Execute loop does
        /// this on first entry).
        /// </summary>
        internal void EnsureCoreCreated()
        {
            if (_coreHandle < 0)
            {
                NceNative.Initialize();
                NceNative.ThreadInit();
                _coreHandle = NceNative.CoreCreate();
            }
        }

        /// <summary>Pulls the register snapshot from the native GuestContext.</summary>
        internal void PullFromNative()
        {
            if (_coreHandle >= 0)
            {
                NceNative.GetContext(_coreHandle, ref _view);
            }
        }

        /// <summary>Pushes the register snapshot to the native GuestContext.</summary>
        internal void PushToNative()
        {
            if (_coreHandle >= 0)
            {
                NceNative.SetContext(_coreHandle, ref _view);
            }
        }

        // --- IExecutionContext ---

        /// <inheritdoc/>
        public ulong Pc => _view.Pc;

        /// <inheritdoc/>
        public long TpidrEl0
        {
            get => (long)_view.TpidrEl0;
            set => _view.TpidrEl0 = (ulong)value;
        }

        /// <inheritdoc/>
        public long TpidrroEl0
        {
            get => (long)_view.TpidrroEl0;
            set => _view.TpidrroEl0 = (ulong)value;
        }

        /// <inheritdoc/>
        public uint Pstate
        {
            get => _view.Pstate;
            set => _view.Pstate = value;
        }

        /// <inheritdoc/>
        public uint Fpcr
        {
            get => _view.Fpcr;
            set => _view.Fpcr = value;
        }

        /// <inheritdoc/>
        public uint Fpsr
        {
            get => _view.Fpsr;
            set => _view.Fpsr = value;
        }

        /// <inheritdoc/>
        public bool IsAarch32
        {
            get => false; // NCE is AArch64-only
            set => throw new NotSupportedException("NCE does not support AArch32");
        }

        /// <inheritdoc/>
        public ulong ThreadUid { get; set; }

        /// <inheritdoc/>
        public bool Running => _running;

        /// <inheritdoc/>
        public ulong GetX(int index)
        {
            return _view.GetX(index);
        }

        /// <inheritdoc/>
        public void SetX(int index, ulong value)
        {
            _view.SetX(index, value);
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
            // Signal the native core to break out of the guest (SIGURG).
            // The Execute loop sees BreakLoop and invokes InterruptCallback.
            if (_coreHandle >= 0)
            {
                NceNative.SignalInterrupt(_coreHandle);
            }
        }

        /// <inheritdoc/>
        public void StopRunning()
        {
            StopRequested = true;
            if (_coreHandle >= 0)
            {
                NceNative.SignalInterrupt(_coreHandle);
            }
        }

        /// <inheritdoc/>
        public void RequestDebugStep()
        {
            throw new NotSupportedException("NCE single-stepping is not implemented yet");
        }

        /// <inheritdoc/>
        public ulong DebugPc
        {
            get => _view.Pc;
            set => _view.Pc = value;
        }

        // --- Internal helpers for the Execute loop ---

        /// <summary>Sets the entry PC before entering the run loop.</summary>
        internal void SetPc(ulong pc) => _view.Pc = pc;

        /// <summary>Sets the entry SP before entering the run loop.</summary>
        internal void SetSp(ulong sp) => _view.Sp = sp;

        /// <summary>Marks the context as inside the native run loop.</summary>
        internal void SetRunning(bool running) => _running = running;

        public void Dispose()
        {
            if (_coreHandle >= 0)
            {
                NceNative.CoreDestroy(_coreHandle);
                _coreHandle = -1;
            }
        }
    }
}

