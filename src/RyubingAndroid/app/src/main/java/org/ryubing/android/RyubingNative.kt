package org.ryubing.android

import com.sun.jna.Library
import com.sun.jna.Native

/**
 * The Kotlin side of the native boundary.
 *
 * Two native libraries are involved:
 *  - libryubing.so    : the NativeAOT emulator core. Its C ABI (see LibRyubing.Native.cs)
 *                       is bound through JNA in [Core].
 *  - libryubingjni.so : the C++ platform shim. It owns the ANativeWindow and registers a
 *                       Vulkan surface factory with the core. Its JNI methods are declared
 *                       here as `external`.
 */
object RyubingNative {

    init {
        // Soft-dep: OpenAL may be packaged with the APK; ignore if absent so the core still loads.
        try {
            System.loadLibrary("openal")
        } catch (_: UnsatisfiedLinkError) {
        }
        // Load the JNI shim as soon as this object is referenced, so its native methods
        // (setSurface / registerSurfaceProvider) are bound before the SurfaceView callbacks
        // can fire. libryubingjni.so has a DT_NEEDED on libryubing.so, so the NativeAOT
        // core is loaded alongside it by the Android linker.
        System.loadLibrary("ryubingjni")
    }

    /** JNA binding to the libryubing.so C ABI. Names match the [UnmanagedCallersOnly] EntryPoints. */
    interface Core : Library {
        fun ryubing_initialize(appDataPath: String): Int
        fun ryubing_set_memory_config(memoryConfiguration: Int, memoryManagerMode: Int)
        fun ryubing_set_system_config(language: Int, region: Int, enableDockedMode: Int, enablePtc: Int)
        fun ryubing_set_system_config_ex(
            enableLowPowerPtc: Int,
            enableFsIntegrity: Int,
            enableInternet: Int,
            ignoreMissingServices: Int,
            matchSystemTime: Int,
            systemTimeOffset: Long,
            tickScalar: Long,
            timeZone: String,
        )
        fun ryubing_set_graphics_config(resScale: Float, enableShaderCache: Int, backendThreading: Int)
        fun ryubing_set_graphics_config_ex(
            vsyncMode: Int,
            customVSyncInterval: Int,
            enableCustomVSync: Int,
            maxAnisotropy: Float,
            aspectRatio: Int,
            antiAliasing: Int,
            scalingFilter: Int,
            scalingFilterLevel: Int,
            enableTextureRecompression: Int,
            enableMacroHle: Int,
            enableColorSpacePassthrough: Int,
            enableSpirvCompilation: Int,
        )
        fun ryubing_set_audio_volume(volume: Float)
        fun ryubing_set_enable_file_log(enable: Int)
        fun ryubing_set_vulkan_driver(driverHandle: Long)
        fun ryubing_load_application(path: String, displayName: String): Int
        fun ryubing_is_running(): Int
        fun ryubing_query_application_info(path: String, displayName: String, outJsonPath: String): Int
        fun ryubing_probe_title_update(path: String, displayName: String, outJsonPath: String): Int
        fun ryubing_get_dlc_content_list(path: String, displayName: String, titleId: Long, outJsonPath: String): Int
        fun ryubing_find_user_save_id(titleId: Long): Long
        fun ryubing_reload_keys()
        fun ryubing_install_firmware(path: String): Int
        fun ryubing_set_button_state(buttonMask: Int)
        fun ryubing_set_stick_state(rightStick: Int, x: Float, y: Float)
        fun ryubing_set_motion_state(ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float)
        fun ryubing_set_window_size(width: Int, height: Int)
        fun ryubing_toggle_pause()
        fun ryubing_set_paused(paused: Int)
        fun ryubing_toggle_mute()
        fun ryubing_adjust_volume(delta: Float)
        fun ryubing_toggle_vsync()
        fun ryubing_adjust_res_scale(direction: Int)
        fun ryubing_adjust_custom_vsync(direction: Int)
        fun ryubing_toggle_turbo()
        fun ryubing_set_turbo_held(held: Int)
        fun ryubing_take_screenshot()
        fun ryubing_stop()
        fun ryubing_shutdown()
    }

    val core: Core by lazy {
        // libryubingjni.so is already loaded in this object's init block (and pulls in
        // libryubing.so via DT_NEEDED); JNA just binds to the already-loaded core here.
        Native.load("ryubing", Core::class.java)
    }

    // --- JNI methods implemented in libryubingjni.cpp ---

    /** Hands the Compose SurfaceView's Surface to the shim (or null to clear it). */
    @JvmStatic
    external fun setSurface(surface: Any?)

    /** Sets ANativeWindow buffer rotation (Surface.ROTATION_*). */
    @JvmStatic
    external fun setSurfaceRotation(rotation: Int)

    /** Registers the shim's Vulkan surface factory with libryubing.so. */
    @JvmStatic
    external fun registerSurfaceProvider()

    /**
     * Opens a custom libvulkan.so via adrenotools. Returns an opaque handle for
     * [Core.ryubing_set_vulkan_driver], or 0 on failure.
     */
    @JvmStatic
    external fun loadVulkanDriver(
        hookLibDir: String,
        customDriverDir: String,
        customDriverName: String,
    ): Long

    /** Ensures the core is bound (native libs load in init) and the surface provider is wired. */
    fun ensureLoaded() {
        // The native libraries are loaded in this object's init block; touching `core`
        // here just forces the JNA binding before we register the surface provider.
        core
        registerSurfaceProvider()
    }
}
