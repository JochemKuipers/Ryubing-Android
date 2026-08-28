package org.ryubing.android.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

data class SaveInfo(val directory: File, val sizeBytes: Long)

class SaveStore(private val appDataPath: String, private val titleId: String) {

    fun find(saveId: String?): SaveInfo? {
        if (saveId.isNullOrBlank()) return null
        val directory = File(appDataPath, "bis/user/save/${saveId.lowercase()}")
        if (!directory.isDirectory) return null
        return SaveInfo(directory, directory.walkTopDown().filter(File::isFile).sumOf(File::length))
    }

    fun backup(save: SaveInfo, output: OutputStream) {
        val metadata = "title_id=${titleId.lowercase()}\nsave_id=${save.directory.name.lowercase()}\n"
        ZipUtil.zipDirectory(
            save.directory,
            output,
            "bis/user/save/${save.directory.name}",
            mapOf(METADATA_FILE to metadata.toByteArray()),
        )
    }

    fun restore(input: InputStream, existingSaveId: String?): SaveInfo {
        val savesRoot = File(appDataPath, "bis/user/save").apply { mkdirs() }
        val temp = File(savesRoot, ".restore_${System.nanoTime()}")
        ZipUtil.extractSafely(input, temp)
        val metadata = File(temp, METADATA_FILE).takeIf(File::isFile)?.readLines()
            ?.mapNotNull { line ->
                val parts = line.split('=', limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            ?.toMap()
            .orEmpty()
        val backupTitleId = metadata["title_id"]
        if (backupTitleId != null && !backupTitleId.equals(titleId, ignoreCase = true)) {
            temp.deleteRecursively()
            throw IOException("This backup belongs to a different game")
        }
        File(temp, METADATA_FILE).delete()
        val saveId = existingSaveId?.takeIf(HEX_ID::matches) ?: run {
            temp.deleteRecursively()
            throw IOException("Play this game once before restoring a save backup")
        }
        val backupSaveId = metadata["save_id"]?.takeIf(HEX_ID::matches)
        val destination = File(savesRoot, saveId.lowercase())
        val parent = destination.parentFile ?: throw IOException("Invalid save directory")
        val staged = File(parent, ".staged_${System.nanoTime()}")
        val previous = File(parent, ".previous_${System.nanoTime()}")
        try {
            val exportedRoot = File(temp, "bis/user/save")
            val source = backupSaveId?.let { File(exportedRoot, it).takeIf(File::isDirectory) }
                ?: File(exportedRoot, saveId).takeIf(File::isDirectory)
                ?: exportedRoot.listFiles()?.singleOrNull { it.isDirectory && HEX_ID.matches(it.name) }
                ?: temp.listFiles()?.singleOrNull { it.isDirectory && HEX_ID.matches(it.name) }
                ?: temp
            if (source.walkTopDown().none(File::isFile)) throw IOException("The archive contains no save files")
            copyTree(source, staged)

            if (destination.exists() && !destination.renameTo(previous)) {
                throw IOException("Could not prepare the existing save for replacement")
            }
            if (!staged.renameTo(destination)) {
                previous.renameTo(destination)
                throw IOException("Could not install the restored save")
            }
            previous.deleteRecursively()
            return find(saveId) ?: throw IOException("Restored save could not be read")
        } finally {
            temp.deleteRecursively()
            staged.deleteRecursively()
            if (previous.exists() && !destination.exists()) previous.renameTo(destination)
        }
    }

    fun clear(save: SaveInfo) {
        save.directory.listFiles()?.forEach { file ->
            if (!file.deleteRecursively()) throw IOException("Could not clear the save")
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

    private companion object {
        const val METADATA_FILE = "ryubing-save.txt"
        val HEX_ID = Regex("[0-9a-fA-F]{16}")
    }
}