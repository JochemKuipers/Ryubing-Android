using LibRyubing.Input;
using LibRyubing.Platform;
using OpenTK.Audio.OpenAL;
using LibHac.Common;
using LibHac.Fs;
using LibHac.Fs.Shim;
using Ryujinx.Audio.Backends.OpenAL;
using Ryujinx.Audio.Integration;
using Ryujinx.Common;
using Ryujinx.Common.Configuration;
using Ryujinx.Common.Configuration.Multiplayer;
using Ryujinx.Common.Logging;
using Ryujinx.Common.Logging.Targets;
using Ryujinx.Common.Utilities;
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
using Ryujinx.Input;
using Ryujinx.Input.HLE;
using Silk.NET.Vulkan;
using SkiaSharp;
using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using ARMeilleure;
using Path = System.IO.Path;

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
        private static NpadManager _npadManager;
        private static AndroidGamepadDriver _gamepadDriver;
        private static AndroidHostUIHandler _uiHandler;

        private static Switch _device;
        private static Thread _gpuThread;
        private static CancellationTokenSource _gpuCancellation;
        private static ManualResetEventSlim _gpuFrameLoopDone;
        private static VulkanLoader _vulkanLoader;
        private static readonly object LifecycleLock = new();

        private static volatile bool _isActive;
        private static long _presentedFrames;
        private static long _bootStartTicks;
        private static volatile bool _isStopped;
        private static volatile bool _screenshotRequested;
        private static readonly object _screenshotLock = new();
        private static float _volumeBeforeMute = 1f;
        private static bool _emulationPaused;

        // Remembered so ExecuteProgram can reload the same container with a new program index.
        private static string _lastApplicationPath;
        private static string _lastApplicationDisplayName;
        private static EmulatorSettings _lastApplicationSettings;
        private static volatile bool _programRelaunchInProgress;

        public static Switch Device => _device;
        public static AndroidGamepadDriver GamepadDriver => _gamepadDriver;
        public static bool IsRunning => _isActive && !_isStopped;

        /// <summary>
        /// Registers a libvulkan.so handle from adrenotools. Pass zero to use the system loader.
        /// Must be called before the first <see cref="LoadApplication"/>.
        /// </summary>
        public static void SetVulkanDriver(nint driverHandle)
        {
            _vulkanLoader?.Dispose();
            _vulkanLoader = driverHandle != nint.Zero ? new VulkanLoader(driverHandle) : null;
        }

        /// <summary>
        /// One-time process initialization. <paramref name="appDataPath"/> is the app's
        /// private storage directory where Ryubing keeps keys, saves, and caches.
        /// </summary>
        public static void Initialize(string appDataPath)
        {
            PlatformInfo.IsBionic = true;

            // OpenTK looks for platform-specific OpenAL sonames; on Android the bundled
            // library is libopenal.so (see native-deps/build-openal.sh / jniLibs).
            OpenALLibraryNameContainer.OverridePath = "libopenal.so";

            // NativeAOT on Android does not support Reflection.Emit; MacroJitCompiler uses
            // DynamicMethod and will crash at runtime. Fall back to the interpreter instead.
            GraphicsConfig.EnableMacroJit = false;

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
            _npadManager = _inputManager.CreateNpadManager();

            _uiHandler = new AndroidHostUIHandler(_accountManager);

            Logger.Notice.Print(LogClass.Application, $"Ryubing Android host initialized at '{appDataPath}'.");
        }

        /// <summary>
        /// Reloads prod.keys/title.keys from the system directory so an already-initialized host
        /// picks up freshly imported keys without a process restart.
        /// </summary>
        public static void ReloadKeys()
        {
            if (_virtualFileSystem == null)
            {
                Logger.Error?.Print(LogClass.Application, "ReloadKeys called before Initialize.");
                return;
            }

            _virtualFileSystem.ReloadKeySet();
            Logger.Notice.Print(LogClass.Application, "Reloaded key set.");
        }

        /// <summary>Returns the existing account-save ID for a title, or zero when none exists.</summary>
        public static ulong FindUserSaveId(ulong titleId)
        {
            if (_libHacHorizonManager == null || _accountManager == null)
            {
                return 0;
            }

            SaveDataFilter filter = SaveDataFilter.Make(
                titleId,
                SaveDataType.Account,
                _accountManager.LastOpenedUser.UserId.ToLibHac(),
                saveDataId: default,
                index: default);
            LibHac.Result result = _libHacHorizonManager.RyujinxClient.Fs.FindSaveDataWithFilter(
                out SaveDataInfo info,
                SaveDataSpaceId.User,
                in filter);
            return result.IsSuccess() ? info.SaveDataId : 0;
        }

        /// <summary>
        /// Installs a firmware package into system storage. <paramref name="path"/> must be a real
        /// file with its original extension (.zip or .xci) — the installer picks the format from the
        /// extension, so the caller copies the SAF selection to a temp file first.
        /// </summary>
        public static bool InstallFirmware(string path)
        {
            if (_contentManager == null)
            {
                Logger.Error?.Print(LogClass.Application, "InstallFirmware called before Initialize.");
                return false;
            }

            try
            {
                SystemVersion version = _contentManager.VerifyFirmwarePackage(path);
                if (version == null)
                {
                    Logger.Error?.Print(LogClass.Application, $"'{path}' is not a valid firmware package.");
                    return false;
                }

                _contentManager.InstallFirmware(path);
                Logger.Notice.Print(LogClass.Application, $"Installed firmware {version.VersionString}.");
                return true;
            }
            catch (Exception ex)
            {
                Logger.Error?.Print(LogClass.Application, $"Failed to install firmware '{path}': {ex}");
                return false;
            }
        }

        /// <summary>
        /// Loads and starts a title. Blocks setting up state, then runs the GPU loop on a
        /// dedicated thread. Returns false if the file could not be loaded.
        /// </summary>
        public static bool LoadApplication(string path, string displayName, EmulatorSettings settings)
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
            GraphicsConfig.MaxAnisotropy = settings.MaxAnisotropy;
            GraphicsConfig.EnableMacroHLE = settings.EnableMacroHLE;
            GraphicsConfig.EnableTextureRecompression = settings.EnableTextureRecompression;
            GraphicsConfig.EnableColorSpacePassthrough = settings.EnableColorSpacePassthrough;
            GraphicsConfig.EnableSpirvCompilationOnVulkan = settings.EnableSpirvCompilationOnVulkan;

            Optimizations.LowPower = settings.EnableLowPowerPtc;

            if (settings.EnableFileLog)
            {
                FileStream logStream = FileLogTarget.PrepareLogFile(Path.Combine(AppDataManager.BaseDirPath, "Logs"));
                if (logStream != null)
                {
                    Logger.AddTarget(new AsyncLogTargetWrapper(new FileLogTarget("file", logStream), 1000));
                }
            }

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
            _device.Gpu.Renderer.ScreenCaptured += OnScreenCaptured;

            // Apply present-time graphics window settings after the renderer exists.
            _device.Gpu.Renderer.Window.SetAntiAliasing(settings.AntiAliasing);
            _device.Gpu.Renderer.Window.SetScalingFilter(settings.ScalingFilter);
            _device.Gpu.Renderer.Window.SetScalingFilterLevel(settings.ScalingFilterLevel);

            if (settings.EnableCustomVSyncInterval)
            {
                _device.CustomVSyncIntervalEnabled = true;
            }

            SystemVersion firmware = _contentManager.GetCurrentFirmwareVersion();
            Logger.Notice.Print(LogClass.Application, $"Firmware version: {firmware?.VersionString ?? "not installed"}");

            if (!LoadByExtension(path, displayName))
            {
                _device.Dispose();
                _device = null;
                return false;
            }

            SetupProgressHandler();
            _npadManager?.Dispose();
            _npadManager = _inputManager.CreateNpadManager();
            _npadManager.Initialize(_device, AndroidInputDefaults.CreateDefaultConfigs(), enableKeyboard: false, enableMouse: false);

            _lastApplicationPath = path;
            _lastApplicationDisplayName = displayName;
            _lastApplicationSettings = settings;

            StartGpuLoop();
            return true;
        }

        private static IRenderer CreateVulkanRenderer(EmulatorSettings settings)
        {
            Vk api = _vulkanLoader?.GetApi() ?? Vk.GetApi();

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

        private static HleConfiguration BuildConfiguration(EmulatorSettings settings)
        {
            // Match desktop: MatchSystemTime forces offset 0 so guest clock tracks host.
            long timeOffset = settings.MatchSystemTime ? 0 : settings.SystemTimeOffset;

            HleConfiguration config = new(
                settings.MemoryConfiguration,
                (SystemLanguage)settings.SystemLanguage,
                (RegionCode)settings.SystemRegion,
                settings.VSyncMode,
                enableDockedMode: settings.EnableDockedMode,
                enablePtc: settings.EnablePtc,
                tickScalar: settings.TickScalar,
                enableInternetAccess: settings.EnableInternetAccess,
                fsIntegrityCheckLevel: settings.EnableFsIntegrityChecks
                    ? LibHac.Tools.FsSystem.IntegrityCheckLevel.ErrorOnInvalid
                    : LibHac.Tools.FsSystem.IntegrityCheckLevel.None,
                fsGlobalAccessLogMode: 0,
                systemTimeOffset: timeOffset,
                timeZone: string.IsNullOrEmpty(settings.TimeZone) ? "UTC" : settings.TimeZone,
                memoryManagerMode: settings.MemoryManagerMode,
                ignoreMissingServices: settings.IgnoreMissingServices,
                aspectRatio: settings.AspectRatio,
                audioVolume: settings.AudioVolume,
                useHypervisor: false,
                multiplayerLanInterfaceId: string.Empty,
                multiplayerMode: MultiplayerMode.Disabled,
                multiplayerDisableP2p: false,
                multiplayerLdnPassphrase: string.Empty,
                multiplayerLdnServer: string.Empty,
                enableGdbStub: false,
                gdbStubPort: 0,
                debuggerSuspendOnStart: false,
                customVSyncInterval: settings.CustomVSyncInterval);

            // NCE: inject engine + patcher from LibRyubing so HLE stays free of that reference.
            // Still default-off; factory also gates on HostMapped + ARM64 host + 64-bit guest.
            if (settings.UseNce)
            {
                if (!LibRyubing.Nce.NceNative.CheckAvailable())
                {
                    Logger.Warning?.Print(LogClass.Application,
                        "UseNce set but libryubing-nce.so is unavailable; falling back to JIT");
                }
                else if (!LibRyubing.Nce.NceAddressSpace.TryReserve(out Ryujinx.Memory.MemoryBlock nceWindow))
                {
                    Logger.Warning?.Print(LogClass.Application,
                        "UseNce set but no identity-mapped address space window could be reserved; falling back to JIT");
                }
                else
                {
                    LibRyubing.Nce.NceNative.ApplyDebugLevel(settings.NceDebugLevel);
                    config.UseNce = true;
                    config.CpuEngineFactory = tickSource => new LibRyubing.Nce.NceEngine(tickSource);
                    config.NceModulePatcher = new LibRyubing.Nce.NceModulePatcher();
                    config.NceAddressSpaceWindow = nceWindow;
                    Logger.Info?.Print(LogClass.Application,
                        $"NCE CPU backend requested and available (debug level={settings.NceDebugLevel}, native={LibRyubing.Nce.NceNative.VersionString})");

                    LibRyubing.Nce.NceSelfTest.RunIfEnabled(settings, (ulong)nceWindow.Pointer, nceWindow.Size);
                }
            }

            return config;
        }

        private static bool LoadByExtension(string path, string displayName)
        {
            try
            {
                if (Directory.Exists(path))
                {
                    return _device.LoadCart(path);
                }

                // On Android the ROM is opened via SAF, so `path` is usually an fd path
                // (e.g. /proc/self/fd/42) with no extension. Detect the format from the
                // original file name instead, falling back to the path for desktop-style loads.
                string forExtension = string.IsNullOrEmpty(displayName) ? path : displayName;

                switch (Path.GetExtension(forExtension).ToLowerInvariant())
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
                Logger.Error?.Print(LogClass.Application, $"Failed to load '{displayName}' ({path}): {ex.Message}");
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
            _gpuFrameLoopDone?.Dispose();
            _gpuFrameLoopDone = new ManualResetEventSlim(false);
            _isActive = true;
            _isStopped = false;
            _presentedFrames = 0;
            _bootStartTicks = Environment.TickCount64;

            _gpuThread = new Thread(GpuLoop) { Name = "Ryubing.GpuLoop" };
            _gpuThread.Start();
        }

        // Mirrors WindowBase.Render(): drive the GPU FIFO and present frames. Presentation
        // targets the Vulkan swapchain bound to the Android surface, so SwapBuffers is a no-op.
        //
        // With BackendThreading, RunLoop blocks this thread in ThreadedRenderer.RenderLoop until
        // the renderer is disposed. The inner callback runs on GPU.MainThread and must signal
        // _gpuFrameLoopDone before returning so Stop/relaunch can dispose without deadlocking.
        private static void GpuLoop()
        {
            try
            {
                _device.Gpu.Renderer.Initialize(GraphicsDebugLevel.None);

                _device.Gpu.Renderer.RunLoop(() =>
                {
                    try
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

                            _npadManager?.Update();

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
                                _device.PresentFrame(() =>
                                {
                                    if (_device.Gpu.Renderer is ThreadedRenderer threaded
                                        && threaded.BaseRenderer is VulkanRenderer vulkanRenderer)
                                    {
                                        NativeJni.SetCurrentTransform((int)vulkanRenderer.CurrentTransform);
                                    }

                                    // Boot milestone for the smoke triage: the first presented frame is
                                    // the point where the guest is provably past a black screen.
                                    long presented = Interlocked.Increment(ref _presentedFrames);
                                    if (presented == 1 || presented == 60 || presented == 600)
                                    {
                                        Logger.Notice.Print(LogClass.Gpu,
                                            $"BOOT|PRESENT frames={presented} uptimeMs={Environment.TickCount64 - _bootStartTicks}");
                                        Logger.Info?.Print(LogClass.Gpu,
                                            $"BOOT|PRESENT frames={presented} uptimeMs={Environment.TickCount64 - _bootStartTicks}");
                                    }
                                });
                            }

                            if (_screenshotRequested)
                            {
                                _screenshotRequested = false;
                                _device.Gpu.Renderer.Screenshot();
                            }

                            if (ticks >= ticksPerFrame)
                            {
                                ticks = Math.Min(ticks - ticksPerFrame, ticksPerFrame);
                            }
                        }

                        if (_device.Gpu.Renderer is ThreadedRenderer threadedRenderer)
                        {
                            threadedRenderer.FlushThreadedCommands();
                        }
                    }
                    finally
                    {
                        _gpuFrameLoopDone.Set();
                    }
                });
            }
            catch (Exception ex)
            {
                Logger.Error?.Print(LogClass.Gpu, $"GPU loop terminated: {ex}");
                _gpuFrameLoopDone?.Set();
            }
        }

        /// <summary>Requests the emulation loop to stop; safe to call from any thread.</summary>
        public static void RequestStop()
        {
            _isStopped = true;
            _isActive = false;
            _gpuCancellation?.Cancel();
        }

        /// <summary>
        /// Hub titles (e.g. SM3DAS) call am ExecuteProgram to switch NCAs. Persistence.Index is
        /// already updated; stop the current session and reload the same path so the new index loads.
        /// </summary>
        public static void ScheduleProgramRelaunch()
        {
            if (_programRelaunchInProgress)
            {
                return;
            }

            _programRelaunchInProgress = true;
            RequestStop();

            Thread relaunch = new(() =>
            {
                try
                {
                    lock (LifecycleLock)
                    {
                        // User Stop() clears this flag before taking the lock.
                        if (!_programRelaunchInProgress)
                        {
                            return;
                        }

                        DisposeEmulationSession(resetUserChannel: false);

                        if (!_programRelaunchInProgress)
                        {
                            _userChannelPersistence = new UserChannelPersistence();
                            return;
                        }

                        if (_userChannelPersistence != null)
                        {
                            _userChannelPersistence.ShouldRestart = false;
                        }

                        string path = _lastApplicationPath;
                        string displayName = _lastApplicationDisplayName ?? string.Empty;
                        EmulatorSettings settings = _lastApplicationSettings;

                        if (string.IsNullOrEmpty(path) || settings == null)
                        {
                            Logger.Error?.Print(LogClass.Application, "Program relaunch requested but no prior LoadApplication state.");
                            _userChannelPersistence = new UserChannelPersistence();
                            return;
                        }

                        Logger.Notice.Print(
                            LogClass.Application,
                            $"Relaunching application for program index {_userChannelPersistence.Index}.");

                        if (!LoadApplication(path, displayName, settings))
                        {
                            Logger.Error?.Print(LogClass.Application, "Program relaunch LoadApplication failed.");
                            _userChannelPersistence = new UserChannelPersistence();
                        }
                    }
                }
                catch (Exception ex)
                {
                    Logger.Error?.Print(LogClass.Application, $"Program relaunch failed: {ex}");
                    _userChannelPersistence = new UserChannelPersistence();
                }
                finally
                {
                    _programRelaunchInProgress = false;
                }
            })
            {
                Name = "Ryubing.ProgramRelaunch",
                IsBackground = true,
            };

            relaunch.Start();
        }

        private static void WaitForGpuFrameLoop(TimeSpan timeout)
        {
            ManualResetEventSlim done = _gpuFrameLoopDone;
            if (done == null)
            {
                return;
            }

            if (!done.Wait(timeout))
            {
                Logger.Warning?.Print(
                    LogClass.Application,
                    $"GPU frame loop did not finish within {timeout.TotalSeconds:0.#}s");
            }
        }

        private static void JoinGpuThread(TimeSpan timeout)
        {
            Thread gpu = _gpuThread;
            _gpuThread = null;
            if (gpu != null && gpu.IsAlive && gpu != Thread.CurrentThread)
            {
                if (!gpu.Join(timeout))
                {
                    Logger.Warning?.Print(
                        LogClass.Application,
                        $"GPU thread did not exit within {timeout.TotalSeconds:0.#}s");
                }
            }
        }

        private static void DisposeEmulationSession(bool resetUserChannel)
        {
            // ThreadedRenderer.RunLoop blocks Ryubing.GpuLoop in RenderLoop until Dispose.
            // Join that thread first and we deadlock; wait for GPU.MainThread instead, dispose
            // (which tears down VkSurfaceKHR / unblocks RenderLoop), then join.
            RequestStop();
            WaitForGpuFrameLoop(TimeSpan.FromSeconds(8));

            try
            {
                _npadManager?.Dispose();
            }
            catch (Exception ex)
            {
                Logger.Warning?.Print(LogClass.Application, $"Npad dispose: {ex.Message}");
            }

            _npadManager = null;

            try
            {
                _device?.Dispose();
            }
            catch (Exception ex)
            {
                Logger.Warning?.Print(LogClass.Application, $"Device dispose: {ex.Message}");
            }

            _device = null;
            JoinGpuThread(TimeSpan.FromSeconds(5));

            _gpuFrameLoopDone?.Dispose();
            _gpuFrameLoopDone = null;
            _emulationPaused = false;
            _screenshotRequested = false;

            if (resetUserChannel)
            {
                _userChannelPersistence = new UserChannelPersistence();
            }
        }

        /// <summary>
        /// Notifies the Vulkan backend that the Android surface changed size. Marks the
        /// swapchain dirty so the next present recreates it from the ANativeWindow extent.
        /// </summary>
        public static void SetWindowSize(int width, int height)
        {
            if (_device?.Gpu?.Renderer is VulkanRenderer vulkan && vulkan.Window != null)
            {
                vulkan.Window.SetSize(width, height);
            }
        }

        // --- Content probing (DLC / updates / library metadata) ---

        public static bool QueryApplicationInfo(string path, string displayName, string outJsonPath) =>
            _virtualFileSystem != null && ContentProbe.QueryApplicationInfo(_virtualFileSystem, path, displayName, outJsonPath);

        public static bool ProbeTitleUpdate(string path, string displayName, string outJsonPath) =>
            _virtualFileSystem != null && ContentProbe.ProbeTitleUpdate(_virtualFileSystem, path, displayName, outJsonPath);

        public static bool GetDlcContentList(string path, string displayName, ulong titleId, string outJsonPath) =>
            _virtualFileSystem != null && ContentProbe.GetDlcContentList(_virtualFileSystem, path, displayName, titleId, outJsonPath);

        // --- Runtime hotkey actions ---

        public static void TogglePause()
        {
            if (_device == null)
            {
                return;
            }

            SetPaused(!_emulationPaused);
        }

        public static void SetPaused(bool paused)
        {
            if (_device == null || _emulationPaused == paused)
            {
                return;
            }

            _emulationPaused = paused;
            _device.System.TogglePauseEmulation(_emulationPaused);
        }

        public static void ToggleMute()
        {
            if (_device == null)
            {
                return;
            }

            if (_device.IsAudioMuted())
            {
                _device.SetVolume(_volumeBeforeMute <= 0f ? 1f : _volumeBeforeMute);
            }
            else
            {
                _volumeBeforeMute = _device.GetVolume();
                _device.SetVolume(0f);
            }
        }

        public static void AdjustVolume(float delta)
        {
            if (_device == null)
            {
                return;
            }

            float next = Math.Clamp(_device.GetVolume() + delta, 0f, 1f);
            _device.SetVolume(next);
            if (next > 0f)
            {
                _volumeBeforeMute = next;
            }
        }

        public static void ToggleVSyncMode()
        {
            if (_device == null)
            {
                return;
            }

            _device.VSyncMode = _device.VSyncMode.Next(_device.CustomVSyncIntervalEnabled);
            _device.UpdateVSyncInterval();
        }

        public static void AdjustResScale(int direction)
        {
            float scale = GraphicsConfig.ResScale;
            scale = direction >= 0 ? Math.Min(scale + 0.5f, 4f) : Math.Max(scale - 0.5f, 0.5f);
            GraphicsConfig.ResScale = scale;
        }

        public static void AdjustCustomVSync(int direction)
        {
            if (_device == null)
            {
                return;
            }

            if (direction >= 0)
            {
                _device.IncrementCustomVSyncInterval();
            }
            else
            {
                _device.DecrementCustomVSyncInterval();
            }
        }

        public static void ToggleTurbo()
        {
            _device?.ToggleTurbo();
        }

        public static void SetTurboHeld(bool held)
        {
            if (_device == null)
            {
                return;
            }

            if (held != _device.TurboMode)
            {
                _device.ToggleTurbo();
            }
        }

        public static void TakeScreenshot()
        {
            _screenshotRequested = true;
        }

        private static void OnScreenCaptured(object sender, ScreenCaptureImageInfo e)
        {
            if (e.Data.Length <= 0 || e.Height <= 0 || e.Width <= 0)
            {
                Logger.Error?.Print(LogClass.Application, $"Screenshot is empty. Size : {e.Data.Length} bytes. Resolution : {e.Width}x{e.Height}", "Screenshot");
                return;
            }

            Task.Run(() =>
            {
                lock (_screenshotLock)
                {
                    string applicationName = _device?.Processes?.ActiveApplication?.Name ?? "screenshot";
                    string sanitized = FileSystemUtils.SanitizeFileName(applicationName);
                    DateTime now = DateTime.Now;
                    string filename = $"{sanitized}_{now.Year}-{now.Month:D2}-{now.Day:D2}_{now.Hour:D2}-{now.Minute:D2}-{now.Second:D2}.png";
                    string directory = Path.Combine(AppDataManager.BaseDirPath, "screenshots");
                    string path = Path.Combine(directory, filename);

                    try
                    {
                        Directory.CreateDirectory(directory);
                    }
                    catch (Exception ex)
                    {
                        Logger.Error?.Print(LogClass.Application, $"Failed to create screenshots dir: {ex.GetType().Name}", "Screenshot");
                        return;
                    }

                    SKColorType colorType = e.IsBgra ? SKColorType.Bgra8888 : SKColorType.Rgba8888;
                    using SKBitmap bitmap = new(new SKImageInfo(e.Width, e.Height, colorType, SKAlphaType.Premul));
                    Marshal.Copy(e.Data, 0, bitmap.GetPixels(), e.Data.Length);

                    using SKBitmap bitmapToSave = new(bitmap.Width, bitmap.Height);
                    using SKCanvas canvas = new(bitmapToSave);
                    canvas.Clear(SKColors.Black);
                    float scaleX = e.FlipX ? -1 : 1;
                    float scaleY = e.FlipY ? -1 : 1;
                    canvas.SetMatrix(SKMatrix.CreateScale(scaleX, scaleY, bitmap.Width / 2f, bitmap.Height / 2f));
                    canvas.DrawBitmap(bitmap, SKPoint.Empty);

                    using SKData data = bitmapToSave.Encode(SKEncodedImageFormat.Png, 100);
                    using FileStream stream = File.OpenWrite(path);
                    data.SaveTo(stream);

                    Logger.Notice.Print(LogClass.Application, $"Screenshot saved to '{path}'.", "Screenshot");
                }
            });
        }

        /// <summary>Stops emulation and disposes the current device. Safe to call repeatedly.</summary>
        public static void Stop()
        {
            lock (LifecycleLock)
            {
                // User exit cancels an in-flight program switch and always clears program index.
                _programRelaunchInProgress = false;
                DisposeEmulationSession(resetUserChannel: true);
            }
        }

        public static void Shutdown()
        {
            Stop();
            _vulkanLoader?.Dispose();
            _vulkanLoader = null;
            _npadManager?.Dispose();
            _npadManager = null;
            _inputManager?.Dispose();
            _virtualFileSystem?.Dispose();
        }
    }
}
