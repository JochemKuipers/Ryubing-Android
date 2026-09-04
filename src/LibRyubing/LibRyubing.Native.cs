using LibRyubing.Input;
using LibRyubing.Platform;
using Ryujinx.Common.Configuration;
using Ryujinx.Common.Logging;
using System;
using System.Runtime.InteropServices;

namespace LibRyubing
{
    /// <summary>
    /// The C ABI exported from libryubing.so. The Kotlin app calls these through JNA;
    /// the C++/JNI shim (libryubingjni.so) supplies the surface provider and callback
    /// table. Every entry point is wrapped so an exception never propagates across the
    /// native boundary (it is logged and turned into a failure return instead).
    /// </summary>
    public static unsafe class Native
    {
        private static readonly EmulatorSettings _settings = new();

        [UnmanagedCallersOnly(EntryPoint = "ryubing_initialize")]
        public static int Initialize(byte* appDataPath)
        {
            return Guard(() => AndroidHost.Initialize(Utf8.ToString(appDataPath) ?? "."));
        }

        [UnmanagedCallersOnly(EntryPoint = "ryubing_set_surface_provider")]
        public static void SetSurfaceProvider(delegate* unmanaged<nint, ulong> createSurface)
        {
            AndroidVulkanWindow.SetSurfaceProvider(createSurface);
        }

        [UnmanagedCallersOnly(EntryPoint = "ryubing_set_callbacks")]
        public static void SetCallbacks(RyubingCallbacks* callbacks)
        {
            if (callbacks != null)
            {
                Interop.SetCallbacks(*callbacks);
            }
        }

        // --- Settings setters (called before LoadApplication) ---

        [UnmanagedCallersOnly(EntryPoint = "ryubing_set_memory_config")]
        public static void SetMemoryConfig(int memoryConfiguration, int memoryManagerMode)
        {
            _settings.MemoryConfiguration = (Ryujinx.HLE.MemoryConfiguration)memoryConfiguration;
            _settings.MemoryManagerMode = (MemoryManagerMode)memoryManagerMode;
        }

        /// <summary>CPU backend flags. useNce != 0 enables NCE when HostMapped + ARM64.
        /// nceDebugLevel: 0=Off 1=Errors 2=Standard 3=Verbose.</summary>
        [UnmanagedCallersOnly(EntryPoint = "ryubing_set_cpu_config")]
        public static void SetCpuConfig(int useNce, int nceDebugLevel)
        {
            _settings.UseNce = useNce != 0;
            _settings.NceDebugLevel = Math.Clamp(nceDebugLevel, 0, 3);
            LibRyubing.Nce.NceNative.ApplyDebugLevel(_settings.NceDebugLevel);
        }

        [UnmanagedCallersOnly(EntryPoint = "ryubing_set_system_config")]
        public static void SetSystemConfig(int language, int region, int enableDockedMode, int enablePtc)
        {
            _settings.SystemLanguage = language;
            _settings.SystemRegion = region;
            _settings.EnableDockedMode = enableDockedMode != 0;
            _settings.EnablePtc = enablePtc != 0;
        }

        [UnmanagedCallersOnly(EntryPoint = "ryubing_set_system_config_ex")]
        public static void SetSystemConfigEx(
            int enableLowPowerPtc,
            int enableFsIntegrity,
            int enableInternet,
            int ignoreMissingServices,
            int matchSystemTime,
            long systemTimeOffset,
            long tickScalar,
            byte* timeZone)
        {
            _settings.EnableLowPowerPtc = enableLowPowerPtc != 0;
            _settings.EnableFsIntegrityChecks = enableFsIntegrity != 0;
            _settings.EnableInternetAccess = enableInternet != 0;
            _settings.IgnoreMissingServices = ignoreMissingServices != 0;
            _settings.MatchSystemTime = matchSystemTime != 0;
            _settings.SystemTimeOffset = systemTimeOffset;
            _settings.TickScalar = tickScalar <= 0 ? 200 : tickScalar;
            _settings.TimeZone = Utf8.ToString(timeZone) ?? "UTC";
        }

        [UnmanagedCallersOnly(EntryPoint = "ryubing_set_graphics_config")]
        public static void SetGraphicsConfig(float resScale, int enableShaderCache, int backendThreading)
        {
            _settings.ResScale = resScale;
            _settings.EnableShaderCache = enableShaderCache != 0;
            _settings.BackendThreading = (BackendThreading)backendThreading;
        }

        [UnmanagedCallersOnly(EntryPoint = "ryubing_set_graphics_config_ex")]
        public static void SetGraphicsConfigEx(
            int vsyncMode,
            int customVSyncInterval,
            int enableCustomVSync,
            float maxAnisotropy,
            int aspectRatio,
            int antiAliasing,
            int scalingFilter,
            int scalingFilterLevel,
            int enableTextureRecompression,
            int enableMacroHle,
            int enableColorSpacePassthrough,
            int enableSpirvCompilation)
        {
            _settings.VSyncMode = (VSyncMode)vsyncMode;
            _settings.CustomVSyncInterval = customVSyncInterval;
            _settings.EnableCustomVSyncInterval = enableCustomVSync != 0;
            _settings.MaxAnisotropy = maxAnisotropy;
            _settings.AspectRatio = (AspectRatio)aspectRatio;
            _settings.AntiAliasing = (AntiAliasing)antiAliasing;
            _settings.ScalingFilter = (ScalingFilter)scalingFilter;
            _settings.ScalingFilterLevel = scalingFilterLevel;
            _settings.EnableTextureRecompression = enableTextureRecompression != 0;
            _settings.EnableMacroHLE = enableMacroHle != 0;
            _settings.EnableColorSpacePassthrough = enableColorSpacePassthrough != 0;
            _settings.EnableSpirvCompilationOnVulkan = enableSpirvCompilation != 0;
        }

        [UnmanagedCallersOnly(EntryPoint = "ryubing_set_audio_volume")]
        public static void SetAudioVolume(float volume)
        {
            _settings.AudioVolume = Math.Clamp(volume, 0f, 1f);
        }

        [UnmanagedCallersOnly(EntryPoint = "ryubing_set_enable_file_log")]
        public static void SetEnableFileLog(int enable)
        {
            _settings.EnableFileLog = enable != 0;
        }

        [UnmanagedCallersOnly(EntryPoint = "ryubing_set_vulkan_driver")]
        public static void SetVulkanDriver(long driverHandle)
        {
            AndroidHost.SetVulkanDriver((nint)driverHandle);
        }

        [UnmanagedCallersOnly(EntryPoint = "ryubing_load_application")]
        public static int LoadApplication(byte* path, byte* displayName)
        {
            bool ok = false;
            Guard(() => ok = AndroidHost.LoadApplication(
                Utf8.ToString(path) ?? string.Empty,
                Utf8.ToString(displayName) ?? string.Empty,
                _settings));
            return ok ? 1 : 0;
        }

        [UnmanagedCallersOnly(EntryPoint = "ryubing_is_running")]
        public static int IsRunning() => AndroidHost.IsRunning ? 1 : 0;

        /// <summary>
        /// Fills out-params with the current HUD snapshot. Returns 1 when emulation is active.
        /// Pointers may be null to skip a field.
        /// </summary>
        [UnmanagedCallersOnly(EntryPoint = "ryubing_get_performance_stats")]
        public static int GetPerformanceStats(
            double* gameFps,
            double* frameTimeMs,
            double* fifoPercent,
            long* presentedFrames,
            int* usingNce)
        {
            if (!AndroidHost.TryGetPerformanceStats(
                    out double fps,
                    out double ft,
                    out double fifo,
                    out long presented,
                    out bool nce))
            {
                return 0;
            }

            if (gameFps != null)
            {
                *gameFps = fps;
            }

            if (frameTimeMs != null)
            {
                *frameTimeMs = ft;
            }

            if (fifoPercent != null)
            {
                *fifoPercent = fifo;
            }

            if (presentedFrames != null)
            {
                *presentedFrames = presented;
            }

            if (usingNce != null)
            {
                *usingNce = nce ? 1 : 0;
            }

            return 1;
        }

        // --- Content probing ---

        [UnmanagedCallersOnly(EntryPoint = "ryubing_query_application_info")]
        public static int QueryApplicationInfo(byte* path, byte* displayName, byte* outJsonPath)
        {
            bool ok = false;
            Guard(() => ok = AndroidHost.QueryApplicationInfo(
                Utf8.ToString(path) ?? string.Empty,
                Utf8.ToString(displayName) ?? string.Empty,
                Utf8.ToString(outJsonPath) ?? string.Empty));
            return ok ? 1 : 0;
        }

        [UnmanagedCallersOnly(EntryPoint = "ryubing_probe_title_update")]
        public static int ProbeTitleUpdate(byte* path, byte* displayName, byte* outJsonPath)
        {
            bool ok = false;
            Guard(() => ok = AndroidHost.ProbeTitleUpdate(
                Utf8.ToString(path) ?? string.Empty,
                Utf8.ToString(displayName) ?? string.Empty,
                Utf8.ToString(outJsonPath) ?? string.Empty));
            return ok ? 1 : 0;
        }

        [UnmanagedCallersOnly(EntryPoint = "ryubing_get_dlc_content_list")]
        public static int GetDlcContentList(byte* path, byte* displayName, ulong titleId, byte* outJsonPath)
        {
            bool ok = false;
            Guard(() => ok = AndroidHost.GetDlcContentList(
                Utf8.ToString(path) ?? string.Empty,
                Utf8.ToString(displayName) ?? string.Empty,
                titleId,
                Utf8.ToString(outJsonPath) ?? string.Empty));
            return ok ? 1 : 0;
        }

        [UnmanagedCallersOnly(EntryPoint = "ryubing_find_user_save_id")]
        public static ulong FindUserSaveId(ulong titleId)
        {
            ulong saveId = 0;
            Guard(() => saveId = AndroidHost.FindUserSaveId(titleId));
            return saveId;
        }

        [UnmanagedCallersOnly(EntryPoint = "ryubing_ensure_user_save_id")]
        public static ulong EnsureUserSaveId(ulong titleId)
        {
            ulong saveId = 0;
            Guard(() => saveId = AndroidHost.EnsureUserSaveId(titleId));
            return saveId;
        }

        // --- System files ---

        [UnmanagedCallersOnly(EntryPoint = "ryubing_reload_keys")]
        public static void ReloadKeys() => Guard(AndroidHost.ReloadKeys);

        [UnmanagedCallersOnly(EntryPoint = "ryubing_install_firmware")]
        public static int InstallFirmware(byte* path)
        {
            bool ok = false;
            Guard(() => ok = AndroidHost.InstallFirmware(Utf8.ToString(path) ?? string.Empty));
            return ok ? 1 : 0;
        }

        // --- Input ---

        [UnmanagedCallersOnly(EntryPoint = "ryubing_set_button_state")]
        public static void SetButtonState(int buttonMask)
        {
            AndroidHost.GamepadDriver?.GetAndroidGamepad(AndroidGamepadDriver.PrimaryGamepadId)?.SetButtons(buttonMask);
        }

        [UnmanagedCallersOnly(EntryPoint = "ryubing_set_stick_state")]
        public static void SetStickState(int rightStick, float x, float y)
        {
            AndroidHost.GamepadDriver?.GetAndroidGamepad(AndroidGamepadDriver.PrimaryGamepadId)?.SetStick(rightStick != 0, x, y);
        }

        [UnmanagedCallersOnly(EntryPoint = "ryubing_set_motion_state")]
        public static void SetMotionState(float ax, float ay, float az, float gx, float gy, float gz)
        {
            AndroidHost.GamepadDriver?.GetAndroidGamepad(AndroidGamepadDriver.PrimaryGamepadId)?
                .SetMotion(new System.Numerics.Vector3(ax, ay, az), new System.Numerics.Vector3(gx, gy, gz));
        }

        [UnmanagedCallersOnly(EntryPoint = "ryubing_set_window_size")]
        public static void SetWindowSize(int width, int height)
        {
            AndroidHost.SetWindowSize(width, height);
        }

        // --- Hotkey actions ---

        [UnmanagedCallersOnly(EntryPoint = "ryubing_toggle_pause")]
        public static void TogglePause() => Guard(AndroidHost.TogglePause);

        [UnmanagedCallersOnly(EntryPoint = "ryubing_set_paused")]
        public static void SetPaused(int paused) => Guard(() => AndroidHost.SetPaused(paused != 0));

        [UnmanagedCallersOnly(EntryPoint = "ryubing_toggle_mute")]
        public static void ToggleMute() => Guard(AndroidHost.ToggleMute);

        [UnmanagedCallersOnly(EntryPoint = "ryubing_adjust_volume")]
        public static void AdjustVolume(float delta) => Guard(() => AndroidHost.AdjustVolume(delta));

        [UnmanagedCallersOnly(EntryPoint = "ryubing_toggle_vsync")]
        public static void ToggleVSync() => Guard(AndroidHost.ToggleVSyncMode);

        [UnmanagedCallersOnly(EntryPoint = "ryubing_adjust_res_scale")]
        public static void AdjustResScale(int direction) => Guard(() => AndroidHost.AdjustResScale(direction));

        [UnmanagedCallersOnly(EntryPoint = "ryubing_adjust_custom_vsync")]
        public static void AdjustCustomVSync(int direction) => Guard(() => AndroidHost.AdjustCustomVSync(direction));

        [UnmanagedCallersOnly(EntryPoint = "ryubing_toggle_turbo")]
        public static void ToggleTurbo() => Guard(AndroidHost.ToggleTurbo);

        [UnmanagedCallersOnly(EntryPoint = "ryubing_set_turbo_held")]
        public static void SetTurboHeld(int held) => Guard(() => AndroidHost.SetTurboHeld(held != 0));

        [UnmanagedCallersOnly(EntryPoint = "ryubing_take_screenshot")]
        public static void TakeScreenshot() => Guard(AndroidHost.TakeScreenshot);

        // --- Lifecycle ---

        [UnmanagedCallersOnly(EntryPoint = "ryubing_stop")]
        public static void Stop() => Guard(AndroidHost.Stop);

        [UnmanagedCallersOnly(EntryPoint = "ryubing_shutdown")]
        public static void Shutdown() => Guard(AndroidHost.Shutdown);

        private static int Guard(Action action)
        {
            try
            {
                action();
                return 1;
            }
            catch (Exception ex)
            {
                Logger.Error?.Print(LogClass.Application, $"Native call failed: {ex}");
                return 0;
            }
        }
    }
}
