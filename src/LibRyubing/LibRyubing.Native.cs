using LibRyubing.Input;
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
    ///
    /// Keep this surface minimal and stable — it is the contract with the app layer.
    /// </summary>
    public static unsafe class Native
    {
        private static readonly EmulatorSettings _settings = new();

        [UnmanagedCallersOnly(EntryPoint = "ryubing_initialize")]
        public static int Initialize(byte* appDataPath)
        {
            return Guard(() => AndroidHost.Initialize(Utf8.ToString(appDataPath) ?? "."));
        }

        /// <summary>
        /// Registers the JNI-provided surface factory:
        /// <c>(VkInstance handle) -> VkSurfaceKHR handle</c>.
        /// Must be called before <see cref="LoadApplication"/>.
        /// </summary>
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
            _settings.MemoryManagerMode = (Ryujinx.Common.Configuration.MemoryManagerMode)memoryManagerMode;
        }

        [UnmanagedCallersOnly(EntryPoint = "ryubing_set_system_config")]
        public static void SetSystemConfig(int language, int region, int enableDockedMode, int enablePtc)
        {
            _settings.SystemLanguage = language;
            _settings.SystemRegion = region;
            _settings.EnableDockedMode = enableDockedMode != 0;
            _settings.EnablePtc = enablePtc != 0;
        }

        [UnmanagedCallersOnly(EntryPoint = "ryubing_set_graphics_config")]
        public static void SetGraphicsConfig(float resScale, int enableShaderCache, int backendThreading)
        {
            _settings.ResScale = resScale;
            _settings.EnableShaderCache = enableShaderCache != 0;
            _settings.BackendThreading = (Ryujinx.Common.Configuration.BackendThreading)backendThreading;
        }

        [UnmanagedCallersOnly(EntryPoint = "ryubing_load_application")]
        public static int LoadApplication(byte* path)
        {
            bool ok = false;
            Guard(() => ok = AndroidHost.LoadApplication(Utf8.ToString(path) ?? string.Empty, _settings));
            return ok ? 1 : 0;
        }

        [UnmanagedCallersOnly(EntryPoint = "ryubing_is_running")]
        public static int IsRunning() => AndroidHost.IsRunning ? 1 : 0;

        // --- Input injection (called each frame / on input events) ---

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

        // --- Lifecycle teardown ---

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
