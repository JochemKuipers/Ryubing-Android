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

    /** JNA binding to the libryubing.so C ABI. Names match the [UnmanagedCallersOnly] EntryPoints. */
    interface Core : Library {
        fun ryubing_initialize(appDataPath: String): Int
        fun ryubing_set_memory_config(memoryConfiguration: Int, memoryManagerMode: Int)
        fun ryubing_set_system_config(language: Int, region: Int, enableDockedMode: Int, enablePtc: Int)
        fun ryubing_set_graphics_config(resScale: Float, enableShaderCache: Int, backendThreading: Int)
        fun ryubing_load_application(path: String): Int
        fun ryubing_is_running(): Int
        fun ryubing_set_button_state(buttonMask: Int)
        fun ryubing_set_stick_state(rightStick: Int, x: Float, y: Float)
        fun ryubing_set_motion_state(ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float)
        fun ryubing_stop()
        fun ryubing_shutdown()
    }

    val core: Core by lazy {
        // libryubingjni.so must be loaded first (it references core exports and JNI_OnLoad).
        System.loadLibrary("ryubingjni")
        Native.load("ryubing", Core::class.java)
    }

    // --- JNI methods implemented in libryubingjni.cpp ---

    /** Hands the Compose SurfaceView's Surface to the shim (or null to clear it). */
    @JvmStatic
    external fun setSurface(surface: Any?)

    /** Registers the shim's Vulkan surface factory with libryubing.so. */
    @JvmStatic
    external fun registerSurfaceProvider()

    /** Ensures both libraries are loaded and the surface provider is wired. */
    fun ensureLoaded() {
        // Touching `core` triggers loadLibrary("ryubingjni") + Native.load("ryubing").
        core
        registerSurfaceProvider()
    }
}
