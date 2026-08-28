package org.ryubing.android.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class ModEntry(
    val name: String,
    val path: String,
    val enabled: Boolean,
    val content: String,
)

class ModsStore(
    private val context: Context,
    private val appDataPath: String,
    private val titleId: String,
) {
    private val titleDir = File(appDataPath, "mods/contents/${titleId.lowercase()}")
    private val metadataFile = File(appDataPath, "games/${titleId.lowercase()}/mods.json")

    fun scan(): List<ModEntry> {
        val states = loadStates()
        if (!titleDir.isDirectory) return emptyList()
        return titleDir.walkTopDown()
            .filter { it.isDirectory && it != titleDir && it.isModRoot() }
            .map { dir ->
                ModEntry(
                    name = dir.name,
                    path = dir.absolutePath,
                    enabled = states[dir.absolutePath] ?: true,
                    content = dir.contentTypes().joinToString(" · "),
                )
            }
            .toList()
            .distinctBy { it.path }
            .sortedBy { it.name.lowercase() }
    }

    fun setEnabled(entry: ModEntry, enabled: Boolean) {
        val entries = scan().associateBy { it.path }.toMutableMap()
        entries[entry.path] = entry.copy(enabled = enabled)
        saveStates(entries.values)
    }

    fun remove(entry: ModEntry) {
        val target = File(entry.path).canonicalFile
        val root = titleDir.canonicalFile
        require(target.path.startsWith(root.path + File.separator)) { "Mod is outside the title directory" }
        if (target.exists() && !target.deleteRecursively()) throw IOException("Could not delete ${entry.name}")
        saveStates(scan())
    }

    fun importZip(uri: Uri, displayName: String): Int {
        val temp = File(context.cacheDir, "mod_import_${System.nanoTime()}")
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
            input.use { ZipUtil.extractSafely(it, temp) }
            importFromDirectory(temp, displayName.substringBeforeLast('.').ifBlank { "Imported mod" })
        } finally {
            temp.deleteRecursively()
        }
    }

    fun importFolder(uri: Uri): Int {
        val root = DocumentFile.fromTreeUri(context, uri) ?: throw IOException("Cannot open selected folder")
        val temp = File(context.cacheDir, "mod_import_${System.nanoTime()}")
        return try {
            copyDocumentTree(root, temp)
            importFromDirectory(temp, root.name ?: "Imported mod")
        } finally {
            temp.deleteRecursively()
        }
    }

    private fun importFromDirectory(root: File, fallbackName: String): Int {
        val discovered = root.walkTopDown().filter { it.isDirectory && it.isModRoot() }.toList()
        val candidates = discovered.filter { candidate ->
            discovered.none { other -> other != candidate && candidate.toPath().startsWith(other.toPath()) }
        }
        if (candidates.isEmpty()) throw IOException("No romfs, exefs or cheats mod found")

        titleDir.mkdirs()
        candidates.forEach { source ->
            val baseName = if (source == root || source.name.equals(titleId, ignoreCase = true)) {
                fallbackName
            } else {
                source.name
            }
            copyTree(source, uniqueDestination(safeName(baseName)))
        }
        saveStates(scan())
        return candidates.size
    }

    private fun uniqueDestination(baseName: String): File {
        var candidate = File(titleDir, baseName)
        var suffix = 2
        while (candidate.exists()) candidate = File(titleDir, "$baseName ($suffix++)")
        return candidate
    }

    private fun loadStates(): Map<String, Boolean> = runCatching {
        val array = JSONObject(metadataFile.readText()).optJSONArray("mods") ?: JSONArray()
        buildMap {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                item.optString("path").takeIf { it.isNotBlank() }?.let {
                    put(it, item.optBoolean("enabled", true))
                }
            }
        }
    }.getOrDefault(emptyMap())

    private fun saveStates(mods: Collection<ModEntry>) {
        val array = JSONArray()
        mods.sortedBy { it.name.lowercase() }.forEach { mod ->
            array.put(
                JSONObject()
                    .put("name", mod.name)
                    .put("path", mod.path)
                    .put("enabled", mod.enabled),
            )
        }
        metadataFile.parentFile?.mkdirs()
        metadataFile.writeText(JSONObject().put("mods", array).toString(2))
    }

    private fun File.isModRoot(): Boolean =
        File(this, "romfs").isDirectory || File(this, "exefs").isDirectory ||
            File(this, "cheats").isDirectory || File(this, "romfs.bin").isFile ||
            File(this, "exefs.nsp").isFile

    private fun File.contentTypes(): List<String> = buildList {
        if (File(this@contentTypes, "romfs").isDirectory || File(this@contentTypes, "romfs.bin").isFile) add("romfs")
        if (File(this@contentTypes, "exefs").isDirectory || File(this@contentTypes, "exefs.nsp").isFile) add("exefs")
        if (File(this@contentTypes, "cheats").isDirectory) add("cheats")
    }

    private fun copyDocumentTree(source: DocumentFile, destination: File) {
        if (source.isDirectory) {
            destination.mkdirs()
            source.listFiles().forEach { child ->
                copyDocumentTree(child, File(destination, safeName(child.name ?: "unnamed")))
            }
        } else {
            destination.parentFile?.mkdirs()
            val input = context.contentResolver.openInputStream(source.uri) ?: throw IOException("Cannot read ${source.name}")
            input.use { from -> destination.outputStream().use(from::copyTo) }
        }
    }

    private fun copyTree(source: File, destination: File) {
        if (source.isDirectory) {
            if (!destination.mkdirs() && !destination.isDirectory) throw IOException("Cannot create $destination")
            source.listFiles()?.forEach { copyTree(it, File(destination, it.name)) }
        } else {
            destination.parentFile?.mkdirs()
            source.copyTo(destination)
        }
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._() -]"), "_").trim().take(100).ifBlank { "Imported mod" }
}