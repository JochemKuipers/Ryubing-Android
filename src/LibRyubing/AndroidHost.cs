using LibRyubing.Input;
using LibRyubing.Platform;
using Ryujinx.Audio.Backends.OpenAL;
using Ryujinx.Audio.Integration;
using Ryujinx.Common.Configuration;
using Ryujinx.Common.Configuration.Multiplayer;
using Ryujinx.Common.Logging;
using Ryujinx.Common.Logging.Targets;
using Ryujinx.Cpu;
using Ryujinx.Graphics.GAL;
using Ryujinx.Graphics.GAL.Multithreading;
using Ryujinx.Graphics.Gpu;
using Ryujinx.Graphics.Vulkan;
using Ryujinx.HLE;
using Ryujinx.HLE.FileSystem;
using Ryujinx.HLE.HOS;
using Ryujinx.HLE.HOS.Services.Account.Acc;
using Ryujinx.HLE.HOS.SystemState;
using Ryujinx.Input.HLE;
using Silk.NET.Vulkan;
using System;
using System.IO;
using System.Threading;

namespace LibRyubing
{
    /// <summary>
    /// The Android headless host: the Ryubing-native equivalent of
    /// <c>HeadlessRyujinx</c>, minus SDL and the desktop window. It owns the emulator
    /// lifecycle (initialization, application loading, the GPU render loop, teardown)
    /// and is driven entirely by the C exports in <see cref="LibRyubing.Native"/>.
    /// </summary>
    internal static class AndroidHost
    {
        private const int TargetFps = 60;

        private static VirtualFileSystem _virtualFileSystem;
        private static LibHacHorizonManager _libHacHorizonManager;
        private static ContentManager _contentManager;
        private static AccountManager _accountManager;
        private static UserChannelPersistence _userChannelPersistence;
        private static InputManager _inputManager;
        private static AndroidGamepadDriver _gamepadDriver;
        private static AndroidHostUIHandler _uiHandler;

        private static Switch _device;
        private static Thread _gpuThread;
        private static CancellationTokenSource _gpuCancellation;

        private static volatile bool _isActive;
        private static volatile bool _isStopped;

        public static Switch Device => _device;
        public static AndroidGamepadDriver GamepadDriver => _gamepadDriver;
        public static bool IsRunning => _isActive && !_isStopped;

        /// <summary>
        /// One-time process initialization. <paramref name="appDataPath"/> is the app's
        /// private storage directory where Ryubing keeps keys, saves, and caches.
        /// </summary>
        public static void Initialize(string appDataPath)
        {
            Logger.AddTarget(new AsyncLogTargetWrapper(new AndroidLogTarget("android"), 1000));
            Logger.SetEnable(LogLevel.Info, true);
            Logger.SetEnable(LogLevel.Warning, true);
            Logger.SetEnable(LogLevel.Error, true);
            Logger.SetEnable(LogLevel.Guest, true);
            Logger.SetEnable(LogLevel.Stub, false);
            Logger.SetEnable(LogLevel.Trace, false);

            AppDataManager.Initialize(appDataPath);

            _virtualFileSystem = VirtualFileSystem.CreateInstance();
            _libHacHorizonManager = new LibHacHorizonManager();
            _libHacHorizonManager.InitializeFsServer(_virtualFileSystem);
            _libHacHorizonManager.InitializeArpServer();
            _libHacHorizonManager.InitializeBcatServer();
            _libHacHorizonManager.InitializeSystemClients();

            _contentManager = new ContentManager(_virtualFileSystem);
            _accountManager = new AccountManager(_libHacHorizonManager.RyujinxClient);
            _userChannelPersistence = new UserChannelPersistence();

            _gamepadDriver = new AndroidGamepadDriver();
            // No hardware keyboard on Android; reuse the gamepad driver as the keyboard
            // slot so NpadManager has both handles (touch keyboard is handled by the applet).
            _inputManager = new InputManager(_gamepadDriver, _gamepadDriver);

            _uiHandler = new AndroidHostUIHandler(_accountManager);

            Logger.Notice.Print(LogClass.Application, $"Ryubing Android host initialized at '{appDataPath}'.");
        }

        /// <summary>
        /// Loads and starts a title. Blocks setting up state, then runs the GPU loop on a
        /// dedicated thread. Returns false if the file could not be loaded.
        /// </summary>
        public static bool LoadApplication(string path, EmulatorSettings settings)
        {
            if (_virtualFileSystem == null)
            {
                Logger.Error?.Print(LogClass.Application, "LoadApplication called before Initialize.");
                return false;
            }

            if (!AndroidVulkanWindow.HasSurfaceProvider)
            {
                Logger.Error?.Print(LogClass.Application, "No Vulkan surface provider registered; call set_surface_provider first.");
                return false;
            }

            GraphicsConfig.EnableShaderCache = settings.EnableShaderCache;
            GraphicsConfig.ResScale = settings.ResScale;
            GraphicsConfig.EnableMacroHLE = true;

            IRenderer renderer = CreateVulkanRenderer(settings);

            HleConfiguration configuration = BuildConfiguration(settings)
                .Configure(
                    _virtualFileSystem,
                    _libHacHorizonManager,
                    _contentManager,
                    _accountManager,
                    _userChannelPersistence,
                    renderer.TryMakeThreaded(settings.BackendThreading),
                    CreateAudioDriver(),
                    _uiHandler);

            _device = new Switch(configuration);

            SystemVersion firmware = _contentManager.GetCurrentFirmwareVersion();
            Logger.Notice.Print(LogClass.Application, $"Firmware version: {firmware?.VersionString ?? "not installed"}");

            if (!LoadByExtension(path))
            {
                _device.Dispose();
                _device = null;
                return false;
            }

            SetupProgressHandler();
            StartGpuLoop();
            return true;
        }

        private static IRenderer CreateVulkanRenderer(EmulatorSettings settings)
        {
            Vk api = Vk.GetApi();

            return new VulkanRenderer(
                api,
                AndroidVulkanWindow.CreateSurface,
                AndroidVulkanWindow.GetRequiredInstanceExtensions,
                settings.PreferredGpuId ?? string.Empty);
        }

        private static IHardwareDeviceDriver CreateAudioDriver()
        {
            if (OpenALHardwareDeviceDriver.IsSupported)
            {
                return new OpenALHardwareDeviceDriver();
            }

            Logger.Warning?.Print(LogClass.Audio, "OpenAL unavailable; audio will be silent.");
            return new Ryujinx.Audio.Backends.Dummy.DummyHardwareDeviceDriver();
        }

        private static HleConfiguration BuildConfiguration(EmulatorSettings settings) =>
            new(
                settings.MemoryConfiguration,
                (SystemLanguage)settings.SystemLanguage,
                (RegionCode)settings.SystemRegion,
                settings.VSyncMode,
                enableDockedMode: settings.EnableDockedMode,
                // PPTC is off by default on Android for stability (see docs/kenji-audit-notes.md).
                enablePtc: settings.EnablePtc,
                tickScalar: ITickSource.RealityTickScalar,
                enableInternetAccess: false,
                fsIntegrityCheckLevel: settings.EnableFsIntegrityChecks
                    ? LibHac.Tools.FsSystem.IntegrityCheckLevel.ErrorOnInvalid
                    : LibHac.Tools.FsSystem.IntegrityCheckLevel.None,
                fsGlobalAccessLogMode: 0,
                systemTimeOffset: 0,
                timeZone: "UTC",
                // HostMappedUnsafe is fastest; the app can downgrade for correctness.
                memoryManagerMode: settings.MemoryManagerMode,
                ignoreMissingServices: false,
                aspectRatio: AspectRatio.Fixed16x9,
                audioVolume: settings.AudioVolume,
                // NCE/hypervisor off by default on Android; ARMeilleure JIT path is used.
                useHypervisor: false,
                multiplayerLanInterfaceId: string.Empty,
                multiplayerMode: MultiplayerMode.Disabled,
                multiplayerDisableP2p: false,
                multiplayerLdnPassphrase: string.Empty,
                multiplayerLdnServer: string.Empty,
                enableGdbStub: false,
                gdbStubPort: 0,
                debuggerSuspendOnStart: false,
                customVSyncInterval: 120);

        private static bool LoadByExtension(string path)
        {
            try
            {
                if (Directory.Exists(path))
                {
                    return _device.LoadCart(path);
                }

                switch (Path.GetExtension(path).ToLowerInvariant())
                {
                    case ".xci":
                        return _device.LoadXci(path);
                    case ".nca":
                        return _device.LoadNca(path);
                    case ".nsp":
                    case ".pfs0":
                        return _device.LoadNsp(path);
                    default:
                        return _device.LoadProgram(path);
                }
            }
            catch (Exception ex)
            {
                Logger.Error?.Print(LogClass.Application, $"Failed to load '{path}': {ex.Message}");
                return false;
            }
        }

        private static void SetupProgressHandler()
        {
            if (_device.Processes.ActiveApplication.DiskCacheLoadState != null)
            {
                _device.Processes.ActiveApplication.DiskCacheLoadState.StateChanged += (state, current, total) =>
                    Interop.ReportProgress("PTC", current, total);
            }

            _device.Gpu.ShaderCacheStateChanged += (state, current, total) =>
                Interop.ReportProgress("Shaders", current, total);
        }

        private static void StartGpuLoop()
        {
            _gpuCancellation = new CancellationTokenSource();
            _isActive = true;
            _isStopped = false;

            _gpuThread = new Thread(GpuLoop) { Name = "Ryubing.GpuLoop" };
            _gpuThread.Start();
        }

        // Mirrors WindowBase.Render(): drive the GPU FIFO and present frames. Presentation
        // targets the Vulkan swapchain bound to the Android surface, so SwapBuffers is a no-op.
        private static void GpuLoop()
        {
            try
            {
                _device.Gpu.Renderer.Initialize(GraphicsDebugLevel.None);

                _device.Gpu.Renderer.RunLoop(() =>
                {
                    _device.Gpu.SetGpuThread();
                    _device.Gpu.InitializeShaderCache(_gpuCancellation.Token);

                    long ticksPerFrame = System.Diagnostics.Stopwatch.Frequency / TargetFps;
                    long ticks = 0;
                    System.Diagnostics.Stopwatch chrono = System.Diagnostics.Stopwatch.StartNew();

                    while (_isActive)
                    {
                        if (_isStopped)
                        {
                            break;
                        }

                        ticks += chrono.ElapsedTicks;
                        chrono.Restart();

                        if (_device.WaitFifo())
                        {
                            _device.Statistics.RecordFifoStart();
                            _device.ProcessFrame();
                            _device.Statistics.RecordFifoEnd();
                        }

                        while (_device.ConsumeFrameAvailable())
                        {
                            _device.PresentFrame(static () => { });
                        }

                        if (ticks >= ticksPerFrame)
                        {
                            ticks = Math.Min(ticks - ticksPerFrame, ticksPerFrame);
                        }
                    }

                    if (_device.Gpu.Renderer is ThreadedRenderer threaded)
                    {
                        threaded.FlushThreadedCommands();
                    }
                });
            }
            catch (Exception ex)
            {
                Logger.Error?.Print(LogClass.Gpu, $"GPU loop terminated: {ex}");
            }
        }

        /// <summary>Requests the emulation loop to stop; safe to call from any thread.</summary>
        public static void RequestStop()
        {
            _isStopped = true;
            _isActive = false;
            _gpuCancellation?.Cancel();
        }

        /// <summary>Stops emulation and disposes the current device. Blocks until the GPU thread exits.</summary>
        public static void Stop()
        {
            RequestStop();

            _gpuThread?.Join(TimeSpan.FromSeconds(5));
            _gpuThread = null;

            _device?.Dispose();
            _device = null;
        }

        public static void Shutdown()
        {
            Stop();
            _inputManager?.Dispose();
            _virtualFileSystem?.Dispose();
        }
    }
}
