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
        fun ryubing_set_graphics_config(resScale: Float, enableShaderCache: Int, backendThreading: Int)
        fun ryubing_set_vulkan_driver(driverHandle: Long)
        fun ryubing_load_application(path: String, displayName: String): Int
        fun ryubing_is_running(): Int
        fun ryubing_reload_keys()
        fun ryubing_install_firmware(path: String): Int
        fun ryubing_set_button_state(buttonMask: Int)
        fun ryubing_set_stick_state(rightStick: Int, x: Float, y: Float)
        fun ryubing_set_motion_state(ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float)
        fun ryubing_set_window_size(width: Int, height: Int)
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
