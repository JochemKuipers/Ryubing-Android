package org.ryubing.android.data

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Resolves the emulator data folder (keys, saves, mods, profiles, caches) from the user's
 * setting. Internal filesDir is the default; the Android/data external files dir or a custom
 * folder can be chosen so saves/mods are reachable from file managers — the system SAF
 * picker cannot enter Android/data, but this app holds All Files Access and uses plain paths.
 *
 * The resolved path is passed to the core via ryubing_initialize() → AppDataManager
 * (which natively supports custom data directories via LaunchMode.Custom).
 */
object DataFolderResolver {

    const val MODE_INTERNAL = 0
    const val MODE_ANDROID_DATA = 1
    const val MODE_CUSTOM = 2

    private const val TAG = "DataFolderResolver"

    /** The emulator data directory for [config]; falls back to internal storage on failure. */
    fun resolve(context: Context, config: EmulatorConfig): File {
        val candidate: File? = when (config.dataFolderMode) {
            MODE_ANDROID_DATA -> context.getExternalFilesDir(null)
            MODE_CUSTOM -> resolveCustom(context, config.dataFolderCustomPath)
            else -> null
        }

        if (candidate == null || candidate.absolutePath == context.filesDir.absolutePath) {
            return context.filesDir
        }

        return try {
            candidate.mkdirs()
            if (candidate.isDirectory && candidate.canWrite()) {
                candidate
            } else {
                Log.w(TAG, "Data folder $candidate is not writable; falling back to internal storage")
                context.filesDir
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to prepare data folder $candidate; falling back to internal storage", e)
            context.filesDir
        }
    }

    private fun resolveCustom(context: Context, raw: String): File? {
        if (raw.isBlank()) return null
        return if (raw.startsWith("content://")) {
            SafPathResolver.resolve(context, Uri.parse(raw))?.let(::File)
        } else {
            File(raw).takeIf { it.absolutePath != "/" }
        }
    }

    /**
     * Copies emulator data (keys, saves, profiles, mods, per-title metadata + caches) from
     * [from] to [to], overwriting existing files. Blocking — call off the main thread.
     * Never deletes anything in [from]; old data is always left in place.
     */
    fun migrateData(from: File, to: File) {
        for (name in listOf("system", "bis", "profiles", "games", "mods", "sdcard")) {
            val src = File(from, name)
            if (src.exists()) {
                copyRecursively(src, File(to, name))
            }
        }
        for (fileName in listOf("Config.json", "Config.json.bak")) {
            val src = File(from, fileName)
            if (src.isFile) {
                runCatching { src.copyTo(File(to, fileName), overwrite = true) }
            }
        }
    }

    private fun copyRecursively(src: File, dest: File) {
        if (src.isDirectory) {
            if (!dest.exists() && !dest.mkdirs() && !dest.isDirectory) return
            src.listFiles()?.forEach { child -> copyRecursively(child, File(dest, child.name)) }
        } else if (src.isFile) {
            runCatching { src.copyTo(dest, overwrite = true) }
        }
    }
}