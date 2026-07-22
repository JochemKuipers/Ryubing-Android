package org.ryubing.android.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Persists imported Turnip driver packages and the user's active selection.
 * Driver zips are extracted under app filesDir/drivers/<id>/ (Kenji/Turnip layout:
 * meta.json + the driver .so) so adrenotools can load them on the next launch.
 */
class DriverRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val driversRoot: File
        get() = File(context.filesDir, "drivers").also { it.mkdirs() }

    /** Writable staging dir adrenotools reads the custom driver from (wiped before each load). */
    val stagingDir: File
        get() = File(context.filesDir, "driver").also { it.mkdirs() }

    fun systemDriver(displayName: String): GpuDriver =
        GpuDriver(GpuDriver.SYSTEM_ID, displayName, extractDir = null, libraryName = null)

    fun loadDrivers(systemDisplayName: String): List<GpuDriver> {
        val imported = parseImportedDrivers()
        return listOf(systemDriver(systemDisplayName)) + imported
    }

    fun findDriver(id: String): GpuDriver? =
        loadDrivers("").find { it.id == id }

    fun loadSelectedId(): String =
        prefs.getString(KEY_SELECTED_ID, GpuDriver.SYSTEM_ID) ?: GpuDriver.SYSTEM_ID

    fun loadSelectedDriver(systemDisplayName: String): GpuDriver {
        val id = loadSelectedId()
        return findDriver(id) ?: systemDriver(systemDisplayName)
    }

    fun saveSelectedId(id: String): Boolean {
        val previous = loadSelectedId()
        if (previous == id) return false
        prefs.edit { putString(KEY_SELECTED_ID, id) }
        // Disk shader caches are driver-specific (Eden wipes them on driver change).
        clearAllShaderCaches()
        return true
    }

    /**
     * Deletes every title's games/.../cache/shader directory under filesDir.
     * Matches Eden's wipe on Vulkan driver switch so pipelines aren't reused across drivers.
     */
    fun clearAllShaderCaches() {
        val gamesDir = File(context.filesDir, "games")
        if (!gamesDir.isDirectory) return
        gamesDir.listFiles()?.forEach { titleDir ->
            if (!titleDir.isDirectory) return@forEach
            val shaderDir = File(titleDir, "cache/shader")
            if (shaderDir.exists()) {
                runCatching { shaderDir.deleteRecursively() }
            }
        }
    }

    /**
     * Copies the selected driver's files into [stagingDir] for adrenotools.
     * Returns the driver metadata, or null when the system loader is selected.
     */
    @Throws(IOException::class)
    fun stageSelectedDriver(systemDisplayName: String): GpuDriver? {
        val driver = loadSelectedDriver(systemDisplayName)
        if (driver.isSystem) return null

        val extractDir = driver.extractDir?.let { File(it) }
            ?: throw IOException("Driver '${driver.displayName}' has no extract directory")
        val libraryName = driver.libraryName
            ?: throw IOException("Driver '${driver.displayName}' has no library name")

        val lib = File(extractDir, libraryName)
        if (!lib.isFile) {
            throw IOException("Driver library missing: ${lib.absolutePath}")
        }

        if (stagingDir.exists()) {
            stagingDir.deleteRecursively()
        }
        stagingDir.mkdirs()

        extractDir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val relative = file.relativeTo(extractDir).path
                val dest = File(stagingDir, relative)
                dest.parentFile?.mkdirs()
                file.copyTo(dest, overwrite = true)
            }

        return driver.copy(extractDir = stagingDir.absolutePath, libraryName = libraryName)
    }

    @Throws(IOException::class)
    fun importDriver(uri: Uri): GpuDriver {
        context.contentResolver.openInputStream(uri)?.use { input ->
            return importDriverStream(input, resolveDisplayName(uri))
        } ?: throw IOException("Cannot open $uri")
    }

    @Throws(IOException::class)
    fun importDriver(file: File): GpuDriver {
        if (!file.isFile) throw IOException("Driver file missing: ${file.absolutePath}")
        file.inputStream().use { input ->
            return importDriverStream(input, file.name.removeSuffix(".zip").ifBlank { "Downloaded driver" })
        }
    }

    @Throws(IOException::class)
    private fun importDriverStream(input: java.io.InputStream, fallbackName: String): GpuDriver {
        val id = UUID.randomUUID().toString()
        val extractDir = File(driversRoot, id)

        if (extractDir.exists()) {
            extractDir.deleteRecursively()
        }
        extractDir.mkdirs()

        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val outFile = File(extractDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(outFile)).use { bos ->
                        zip.copyTo(bos)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val meta = readMetadata(extractDir, fallbackName)
        val libraryName = meta.libraryName
            ?: findDriverLibraryName(extractDir)
            ?: throw IOException("No driver .so found in package")

        if (!File(extractDir, libraryName).isFile) {
            throw IOException("Driver library '$libraryName' not found after extraction")
        }

        val driver = GpuDriver(
            id = id,
            displayName = meta.displayName,
            extractDir = extractDir.absolutePath,
            libraryName = libraryName,
        )

        val drivers = parseImportedDrivers().toMutableList()
        drivers += driver
        persistImportedDrivers(drivers)
        return driver
    }

    fun deleteDriver(id: String) {
        if (id == GpuDriver.SYSTEM_ID) return
        File(driversRoot, id).deleteRecursively()
        persistImportedDrivers(parseImportedDrivers().filterNot { it.id == id })
        if (loadSelectedId() == id) {
            // Falling back to system is a driver change — wipe caches.
            saveSelectedId(GpuDriver.SYSTEM_ID)
        }
    }

    private data class ImportMetadata(val displayName: String, val libraryName: String?)

    private fun readMetadata(extractDir: File, fallbackName: String): ImportMetadata {
        val metaFile = File(extractDir, "meta.json")
        if (!metaFile.isFile) {
            return ImportMetadata(fallbackName, libraryName = null)
        }

        val json = JSONObject(metaFile.readText())
        val name = json.optString("name").takeIf { it.isNotBlank() }
            ?: json.optString("packageName").takeIf { it.isNotBlank() }
            ?: fallbackName
        val libraryName = json.optString("libraryName").takeIf { it.isNotBlank() }
        return ImportMetadata(name, libraryName)
    }

    private fun findDriverLibraryName(extractDir: File): String? {
        val candidates = extractDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".so") }
            .map { it.name }
            .toList()

        return candidates.firstOrNull { it.startsWith("libvulkan") }
            ?: candidates.firstOrNull { it.contains("vulkan", ignoreCase = true) }
            ?: candidates.firstOrNull()
    }

    private fun resolveDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { return it.removeSuffix(".zip") }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".zip")?.takeIf { it.isNotBlank() }
            ?: "Imported driver"
    }

    private fun parseImportedDrivers(): List<GpuDriver> {
        val raw = prefs.getString(KEY_IMPORTED, "[]") ?: "[]"
        val array = JSONArray(raw)
        val drivers = mutableListOf<GpuDriver>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val id = obj.getString("id")
            val name = obj.getString("name")
            val extractDir = obj.optString("extractDir").takeIf { it.isNotBlank() }
                ?: obj.optString("path").takeIf { it.isNotBlank() && !it.endsWith(".zip") }
            val libraryName = obj.optString("libraryName").takeIf { it.isNotBlank() }

            if (extractDir != null && libraryName != null && File(extractDir, libraryName).isFile) {
                drivers += GpuDriver(id, name, extractDir, libraryName)
            }
        }
        if (drivers.size != array.length()) {
            persistImportedDrivers(drivers)
        }
        return drivers
    }

    private fun persistImportedDrivers(drivers: List<GpuDriver>) {
        val array = JSONArray()
        for (driver in drivers) {
            array.put(
                JSONObject()
                    .put("id", driver.id)
                    .put("name", driver.displayName)
                    .put("extractDir", driver.extractDir)
                    .put("libraryName", driver.libraryName),
            )
        }
        prefs.edit { putString(KEY_IMPORTED, array.toString()) }
    }

    private companion object {
        const val PREFS_NAME = "ryubing_drivers"
        const val KEY_IMPORTED = "imported"
        const val KEY_SELECTED_ID = "selected_id"
    }
}
