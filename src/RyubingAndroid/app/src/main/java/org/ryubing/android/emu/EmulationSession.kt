package org.ryubing.android.emu

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import org.json.JSONObject
import org.ryubing.android.R
import org.ryubing.android.RyubingNative
import org.ryubing.android.data.DriverRepository
import org.ryubing.android.data.AppLifecycleStore
import org.ryubing.android.data.EmulatorConfig
import org.ryubing.android.data.GameEntry
import org.ryubing.android.input.HotkeyAction
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
    private val lifecycleStore: AppLifecycleStore,
) {

    private val buttonState = AtomicInteger(0)
    @Volatile private var initialized = false
    @Volatile private var stopThread: Thread? = null

    // The ROM is opened via SAF; the descriptor must stay open for the whole session because
    // the core reads content from it on demand (the fd path below points at this descriptor).
    @Volatile private var romFd: ParcelFileDescriptor? = null
    private var lastWindowWidth = 0
    private var lastWindowHeight = 0

    /** Invoked on the UI thread when the ShowUi hotkey fires. */
    @Volatile var onShowUiRequested: (() -> Unit)? = null

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
            ryubing_set_cpu_config(config.useNce.toInt())
            ryubing_set_system_config(
                config.systemLanguage,
                config.systemRegion,
                config.dockedMode.toInt(),
                config.enablePptc.toInt(),
            )
            ryubing_set_system_config_ex(
                config.enableLowPowerPtc.toInt(),
                config.enableFsIntegrity.toInt(),
                config.enableInternet.toInt(),
                config.ignoreMissingServices.toInt(),
                config.matchSystemTime.toInt(),
                config.systemTimeOffset,
                config.tickScalar,
                config.timeZone,
            )
            ryubing_set_graphics_config(config.resScale, config.enableShaderCache.toInt(), config.backendThreading)
            ryubing_set_graphics_config_ex(
                config.vsyncMode,
                config.customVSyncInterval,
                config.enableCustomVSync.toInt(),
                config.maxAnisotropy,
                config.aspectRatio,
                config.antiAliasing,
                config.scalingFilter,
                config.scalingFilterLevel,
                config.enableTextureRecompression.toInt(),
                config.enableMacroHle.toInt(),
                config.enableColorSpacePassthrough.toInt(),
                config.enableSpirvCompilationOnVulkan.toInt(),
            )
            ryubing_set_audio_volume(if (config.audioMuted) 0f else config.audioVolume)
            ryubing_set_enable_file_log(config.enableFileLog.toInt())
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
        // Finish any in-flight async stop before loading again.
        stopThread?.let { t ->
            runCatching { t.join(3000) }
            if (t === stopThread) stopThread = null
        }

        closeRomFd()
        val pfd = try {
            contentResolver.openFileDescriptor(game.uri, "r")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open ${game.uri}", e)
            null
        } ?: return false

        romFd = pfd
        val fdPath = "/proc/self/fd/${pfd.fd}"

        val driverHandle = VulkanDriverLoader.apply(
            appContext,
            driverRepository,
            appContext.getString(R.string.system_driver),
        )
        lifecycleStore.markSessionStarted(systemDriver = driverHandle == 0L)

        val ok = RyubingNative.core.ryubing_load_application(fdPath, game.fileName) == 1
        if (!ok) {
            lifecycleStore.markSessionStopped()
            Log.e(TAG, "Failed to load ${game.title} ($fdPath)")
            closeRomFd()
        }
        return ok
    }

    private fun closeRomFd() {
        romFd?.let { runCatching { it.close() } }
        romFd = null
    }

    // --- Content probing (requires initialize()) ---

    data class ApplicationInfo(
        val titleId: String = "",
        val titleName: String = "",
        val version: String = "0",
        val developer: String = "",
    )

    data class TitleUpdateInfo(
        val titleId: String = "",
        val version: Long = 0,
        val displayVersion: String = "0",
        val path: String = "",
    )

    /**
     * Probes a ROM path (fd or real filesystem) for base-application metadata.
     * Blocking — call off the main thread after [initialize].
     */
    fun queryApplicationInfo(fdPath: String, displayName: String): ApplicationInfo? {
        initialize()
        val out = File(appContext.cacheDir, "query_app_${System.nanoTime()}.json")
        return try {
            if (RyubingNative.core.ryubing_query_application_info(fdPath, displayName, out.absolutePath) != 1) {
                return null
            }
            val obj = JSONObject(out.readText())
            ApplicationInfo(
                titleId = obj.optString("titleId", ""),
                titleName = obj.optString("titleName", displayName),
                version = obj.optString("version", "0"),
                developer = obj.optString("developer", ""),
            )
        } catch (e: Exception) {
            Log.e(TAG, "queryApplicationInfo failed for $displayName", e)
            null
        } finally {
            out.delete()
        }
    }

    fun probeTitleUpdate(path: String, displayName: String): TitleUpdateInfo? {
        initialize()
        val out = File(appContext.cacheDir, "probe_update_${System.nanoTime()}.json")
        return try {
            if (RyubingNative.core.ryubing_probe_title_update(path, displayName, out.absolutePath) != 1) {
                return null
            }
            val obj = JSONObject(out.readText())
            TitleUpdateInfo(
                titleId = obj.optString("titleId", ""),
                version = obj.optLong("version", 0L),
                displayVersion = obj.optString("displayVersion", "0"),
                path = obj.optString("path", path),
            )
        } catch (e: Exception) {
            Log.e(TAG, "probeTitleUpdate failed for $displayName", e)
            null
        } finally {
            out.delete()
        }
    }

    /**
     * Returns raw DLC container JSON (snake_case list) written by the core, or null.
     * [titleIdHex] is the base game title ID (16 hex chars).
     */
    fun getDlcContentListJson(path: String, displayName: String, titleIdHex: String): String? {
        initialize()
        val titleIdLong = titleIdHex.toLongOrNull(16) ?: return null
        val out = File(appContext.cacheDir, "dlc_list_${System.nanoTime()}.json")
        return try {
            if (RyubingNative.core.ryubing_get_dlc_content_list(
                    path,
                    displayName,
                    titleIdLong,
                    out.absolutePath,
                ) != 1
            ) {
                return null
            }
            out.readText()
        } catch (e: Exception) {
            Log.e(TAG, "getDlcContentList failed for $displayName", e)
            null
        } finally {
            out.delete()
        }
    }

    /** Returns the active user's existing account-save ID for [titleIdHex], or null. */
    fun findUserSaveId(titleIdHex: String): String? {
        val titleId = titleIdHex.toULongOrNull(16) ?: return null
        initialize()
        return RyubingNative.core.ryubing_find_user_save_id(titleId.toLong())
            .toULong()
            .takeIf { it != 0uL }
            ?.toString(16)
            ?.padStart(16, '0')
    }

    // --- Hotkeys ---

    fun performHotkey(action: HotkeyAction) {
        when (action) {
            HotkeyAction.ToggleVSync -> RyubingNative.core.ryubing_toggle_vsync()
            HotkeyAction.Screenshot -> RyubingNative.core.ryubing_take_screenshot()
            HotkeyAction.ShowUi -> onShowUiRequested?.invoke()
            HotkeyAction.Pause -> RyubingNative.core.ryubing_toggle_pause()
            HotkeyAction.ToggleMute -> RyubingNative.core.ryubing_toggle_mute()
            HotkeyAction.ResScaleUp -> RyubingNative.core.ryubing_adjust_res_scale(1)
            HotkeyAction.ResScaleDown -> RyubingNative.core.ryubing_adjust_res_scale(-1)
            HotkeyAction.VolumeUp -> RyubingNative.core.ryubing_adjust_volume(0.05f)
            HotkeyAction.VolumeDown -> RyubingNative.core.ryubing_adjust_volume(-0.05f)
            HotkeyAction.CustomVSyncInc -> RyubingNative.core.ryubing_adjust_custom_vsync(1)
            HotkeyAction.CustomVSyncDec -> RyubingNative.core.ryubing_adjust_custom_vsync(-1)
            HotkeyAction.TurboMode -> RyubingNative.core.ryubing_toggle_turbo()
        }
    }

    fun setPaused(paused: Boolean) =
        RyubingNative.core.ryubing_set_paused(paused.toInt())

    fun setTurboHeld(held: Boolean) =
        RyubingNative.core.ryubing_set_turbo_held(held.toInt())

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

    fun copyUriTo(uri: Uri, dest: File) {
        dest.parentFile?.mkdirs()
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
        synchronized(this) {
            if (initialized) {
                try {
                    RyubingNative.core.ryubing_stop()
                } catch (e: Exception) {
                    Log.e(TAG, "ryubing_stop failed", e)
                }
            }
            lastWindowWidth = 0
            lastWindowHeight = 0
            closeRomFd()
        }
    }

    /** Non-blocking stop for UI exit paths (avoids ANR on main). */
    fun stopAsync() {
        val existing = stopThread
        if (existing?.isAlive == true) return
        stopThread = Thread({
            try {
                stop()
            } finally {
                if (Thread.currentThread() === stopThread) stopThread = null
            }
        }, "Ryubing.Stop").also { it.start() }
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
