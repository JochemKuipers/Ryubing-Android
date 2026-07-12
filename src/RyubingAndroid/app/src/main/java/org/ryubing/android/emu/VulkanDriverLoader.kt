package org.ryubing.android.emu

import android.content.Context
import android.util.Log
import org.ryubing.android.RyubingNative
import org.ryubing.android.data.DriverRepository
import org.ryubing.android.data.GpuDriver

/**
 * Loads the persisted Turnip driver selection through adrenotools and registers the
 * resulting libvulkan handle with libryubing.so.
 */
object VulkanDriverLoader {

    private const val TAG = "VulkanDriverLoader"

    /**
     * Applies the user's driver choice. Safe to call before each game launch.
     * Returns the adrenotools handle (0 = system loader).
     */
    fun apply(context: Context, repository: DriverRepository, systemDriverLabel: String): Long {
        return try {
            val staged = repository.stageSelectedDriver(systemDriverLabel)
            if (staged == null) {
                RyubingNative.core.ryubing_set_vulkan_driver(0)
                Log.i(TAG, "Using system Vulkan driver")
                return 0
            }

            val libraryName = staged.libraryName
                ?: throw IllegalStateException("Staged driver has no library name")
            val stagingDir = staged.extractDir
                ?: throw IllegalStateException("Staged driver has no directory")

            val handle = RyubingNative.loadVulkanDriver(
                hookLibDir = context.applicationInfo.nativeLibraryDir + "/",
                customDriverDir = stagingDir + "/",
                customDriverName = libraryName,
            )

            if (handle == 0L) {
                Log.e(TAG, "adrenotools_open_libvulkan failed; falling back to system driver")
                RyubingNative.core.ryubing_set_vulkan_driver(0)
                return 0
            }

            RyubingNative.core.ryubing_set_vulkan_driver(handle)
            Log.i(TAG, "Loaded custom driver ${staged.displayName} ($libraryName) handle=$handle")
            handle
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load custom Vulkan driver; using system loader", e)
            RyubingNative.core.ryubing_set_vulkan_driver(0)
            0
        }
    }
}
