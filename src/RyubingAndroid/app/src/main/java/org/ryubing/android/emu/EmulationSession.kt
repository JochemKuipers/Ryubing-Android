package org.ryubing.android.emu

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import org.ryubing.android.R
import org.ryubing.android.RyubingNative
import org.ryubing.android.data.DriverRepository
import org.ryubing.android.data.EmulatorConfig
import org.ryubing.android.data.GameEntry
import org.ryubing.android.input.SwitchButton
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-level glue over the [RyubingNative] C ABI. Owns the current button state and
 * forwards lifecycle + input to libryubing.so. One instance per running title.
 */
class EmulationSession(
    private val appContext: Context,
    private val appDataPath: String,
    private val contentResolver: ContentResolver,
    private val driverRepository: DriverRepository,
) {

    private val buttonState = AtomicInteger(0)
    @Volatile private var initialized = false

    // The ROM is opened via SAF; the descriptor must stay open for the whole session because
    // the core reads content from it on demand (the fd path below points at this descriptor).
    @Volatile private var romFd: ParcelFileDescriptor? = null
    private var lastWindowWidth = 0
    private var lastWindowHeight = 0

    fun initialize() {
        if (initialized) return
        RyubingNative.ensureLoaded()
        val rc = RyubingNative.core.ryubing_initialize(appDataPath)
        check(rc == 1) { "ryubing_initialize failed" }
        initialized = true
    }

    fun applyConfig(config: EmulatorConfig) {
        RyubingNative.core.apply {
            ryubing_set_memory_config(config.memoryConfiguration, config.memoryManagerMode)
            ryubing_set_system_config(config.systemLanguage, config.systemRegion, config.dockedMode.toInt(), config.enablePptc.toInt())
            ryubing_set_graphics_config(config.resScale, config.enableShaderCache.toInt(), config.backendThreading)
        }
    }

    /** The SurfaceView hands us its Surface; forward it to the JNI shim. */
    fun setSurface(surface: Surface?) = RyubingNative.setSurface(surface)

    fun setSurfaceRotation(rotation: Int) = RyubingNative.setSurfaceRotation(rotation)

    fun setWindowSize(width: Int, height: Int) {
        if (width > 0 && height > 0 && (width != lastWindowWidth || height != lastWindowHeight)) {
            lastWindowWidth = width
            lastWindowHeight = height
            RyubingNative.core.ryubing_set_window_size(width, height)
        }
    }

    /**
     * Opens [game]'s SAF content URI and hands the core an openable path. Android content URIs
     * can't be opened as filesystem paths, so we resolve the URI to a real file descriptor and
     * pass "/proc/self/fd/N" (kept valid by holding [romFd] open). The original file name is
     * passed alongside so the core can still detect the ROM format (the fd path has no extension).
     */
    fun start(game: GameEntry): Boolean {
        closeRomFd()
        val pfd = try {
            contentResolver.openFileDescriptor(game.uri, "r")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open ${game.uri}", e)
            null
        } ?: return false

        romFd = pfd
        val fdPath = "/proc/self/fd/${pfd.fd}"

        VulkanDriverLoader.apply(
            appContext,
            driverRepository,
            appContext.getString(R.string.system_driver),
        )

        val ok = RyubingNative.core.ryubing_load_application(fdPath, game.title) == 1
        if (!ok) {
            Log.e(TAG, "Failed to load ${game.title} ($fdPath)")
            closeRomFd()
        }
        return ok
    }

    private fun closeRomFd() {
        romFd?.let { runCatching { it.close() } }
        romFd = null
    }

    // --- System files (keys / firmware) ---

    /**
     * Copies a user-picked prod.keys into the app's private system directory and reloads the
     * key set so it takes effect immediately (and is loaded on every subsequent launch).
     * Blocking I/O — call off the main thread.
     */
    fun importProdKeys(uri: Uri): Boolean {
        return try {
            val systemDir = File(appDataPath, "system").apply { mkdirs() }
            copyUriTo(uri, File(systemDir, "prod.keys"))
            initialize()
            RyubingNative.core.ryubing_reload_keys()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import prod.keys from $uri", e)
            false
        }
    }

    /**
     * Installs a firmware package. The core's installer picks the format from the file extension,
     * so we copy the SAF selection to a temp file named after [displayName] before handing over the
     * real path. Blocking I/O (potentially hundreds of MB) — call off the main thread.
     */
    fun installFirmware(uri: Uri, displayName: String): Boolean {
        initialize()
        val ext = displayName.substringAfterLast('.', "zip").lowercase()
        val temp = File(appDataPath, "firmware_import.$ext")
        return try {
            copyUriTo(uri, temp)
            RyubingNative.core.ryubing_install_firmware(temp.absolutePath) == 1
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install firmware from $uri", e)
            false
        } finally {
            temp.delete()
        }
    }

    private fun copyUriTo(uri: Uri, dest: File) {
        (contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    val isRunning: Boolean get() = initialized && RyubingNative.core.ryubing_is_running() == 1

    // --- Input ---

    fun setButton(button: SwitchButton, pressed: Boolean) {
        val updated = if (pressed) buttonState.get() or button.bit else buttonState.get() and button.bit.inv()
        buttonState.set(updated)
        RyubingNative.core.ryubing_set_button_state(updated)
    }

    fun setStick(right: Boolean, x: Float, y: Float) =
        RyubingNative.core.ryubing_set_stick_state(if (right) 1 else 0, x, y)

    fun setMotion(ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float) =
        RyubingNative.core.ryubing_set_motion_state(ax, ay, az, gx, gy, gz)

    fun stop() {
        if (initialized) RyubingNative.core.ryubing_stop()
        lastWindowWidth = 0
        lastWindowHeight = 0
        closeRomFd()
    }

    fun shutdown() {
        if (initialized) RyubingNative.core.ryubing_shutdown()
        initialized = false
        closeRomFd()
    }

    private fun Boolean.toInt() = if (this) 1 else 0

    companion object {
        private const val TAG = "EmulationSession"
    }
}
