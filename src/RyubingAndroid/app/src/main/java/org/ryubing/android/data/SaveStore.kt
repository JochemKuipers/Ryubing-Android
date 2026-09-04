package org.ryubing.android.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

data class SaveInfo(val directory: File, val sizeBytes: Long)

sealed class SaveArchiveKind {
    data object Ryubing : SaveArchiveKind()
    data class Eden(val titleIds: List<String>) : SaveArchiveKind()
    data object Unknown : SaveArchiveKind()
}

/** Extracted archive kept on disk until [SaveStore.discard] or a successful restore. */
data class ExtractedSaveArchive(val root: File, val kind: SaveArchiveKind)

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

    fun extractAndInspect(input: InputStream): ExtractedSaveArchive {
        val savesRoot = File(appDataPath, "bis/user/save").apply { mkdirs() }
        val temp = File(savesRoot, ".restore_${System.nanoTime()}")
        try {
            ZipUtil.extractSafely(input, temp)
            return ExtractedSaveArchive(temp, inspect(temp))
        } catch (e: Exception) {
            temp.deleteRecursively()
            throw e
        }
    }

    fun discard(extracted: ExtractedSaveArchive) {
        extracted.root.deleteRecursively()
    }

    fun restore(input: InputStream, existingSaveId: String?, selectedEdenTitleId: String? = null): SaveInfo {
        val extracted = extractAndInspect(input)
        return restoreExtracted(extracted, existingSaveId, selectedEdenTitleId)
    }

    fun restoreExtracted(
        extracted: ExtractedSaveArchive,
        existingSaveId: String?,
        selectedEdenTitleId: String? = null,
    ): SaveInfo {
        val savesRoot = File(appDataPath, "bis/user/save").apply { mkdirs() }
        val destination = existingSaveId?.takeIf(HEX_ID::matches)?.let { File(savesRoot, it.lowercase()) }
            ?: run {
                discard(extracted)
                throw IOException("Could not create a save slot for this game")
            }
        val parent = destination.parentFile ?: run {
            discard(extracted)
            throw IOException("Invalid save directory")
        }
        val staged = File(parent, ".staged_${System.nanoTime()}")
        val previous = File(parent, ".previous_${System.nanoTime()}")
        try {
            val source = when (val kind = extracted.kind) {
                is SaveArchiveKind.Ryubing -> resolveRyubingSource(extracted.root, destination.name)
                is SaveArchiveKind.Eden -> resolveEdenSource(extracted.root, kind, selectedEdenTitleId)
                SaveArchiveKind.Unknown -> throw IOException("Unrecognized save archive")
            }
            if (source.walkTopDown().none(File::isFile)) throw IOException("The archive contains no save files")

            when (extracted.kind) {
                is SaveArchiveKind.Eden -> {
                    staged.mkdirs()
                    copyTree(source, File(staged, "0"))
                }
                else -> copyTree(source, staged)
            }

            if (destination.exists() && !destination.renameTo(previous)) {
                throw IOException("Could not prepare the existing save for replacement")
            }
            if (!staged.renameTo(destination)) {
                previous.renameTo(destination)
                throw IOException("Could not install the restored save")
            }
            previous.deleteRecursively()
            return find(destination.name) ?: throw IOException("Restored save could not be read")
        } finally {
            discard(extracted)
            staged.deleteRecursively()
            if (previous.exists() && !destination.exists()) previous.renameTo(destination)
        }
    }

    fun clear(save: SaveInfo) {
        save.directory.listFiles()?.forEach { file ->
            if (!file.deleteRecursively()) throw IOException("Could not clear the save")
        }
    }

    private fun inspect(root: File): SaveArchiveKind {
        val hasMetadata = File(root, METADATA_FILE).isFile
        val hasBisSave = File(root, "bis/user/save").isDirectory
        if (hasMetadata || hasBisSave) return SaveArchiveKind.Ryubing

        val titleIds = root.listFiles()
            ?.filter { it.isDirectory && HEX_ID.matches(it.name) }
            ?.map { it.name }
            ?.sortedBy { it.lowercase() }
            .orEmpty()
        if (titleIds.isNotEmpty()) return SaveArchiveKind.Eden(titleIds)
        return SaveArchiveKind.Unknown
    }

    private fun resolveRyubingSource(temp: File, saveId: String): File {
        val metadata = File(temp, METADATA_FILE).takeIf(File::isFile)?.readLines()
            ?.mapNotNull { line ->
                val parts = line.split('=', limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            ?.toMap()
            .orEmpty()
        val backupTitleId = metadata["title_id"]
        if (backupTitleId != null && !backupTitleId.equals(titleId, ignoreCase = true)) {
            throw IOException("This backup belongs to a different game")
        }
        File(temp, METADATA_FILE).delete()
        val backupSaveId = metadata["save_id"]?.takeIf(HEX_ID::matches)
        val exportedRoot = File(temp, "bis/user/save")
        return backupSaveId?.let { File(exportedRoot, it).takeIf(File::isDirectory) }
            ?: File(exportedRoot, saveId).takeIf(File::isDirectory)
            ?: exportedRoot.listFiles()?.singleOrNull { it.isDirectory && HEX_ID.matches(it.name) }
            ?: temp.listFiles()?.singleOrNull { it.isDirectory && HEX_ID.matches(it.name) }
            ?: temp
    }

    private fun resolveEdenSource(
        temp: File,
        kind: SaveArchiveKind.Eden,
        selectedEdenTitleId: String?,
    ): File {
        val chosen = when {
            selectedEdenTitleId != null -> selectedEdenTitleId
            kind.titleIds.size == 1 -> kind.titleIds.single()
            else -> kind.titleIds.firstOrNull { it.equals(titleId, ignoreCase = true) }
                ?: throw IOException("Select which game save to restore from this archive")
        }
        if (!chosen.equals(titleId, ignoreCase = true)) {
            throw IOException("This backup belongs to a different game")
        }
        val titleDir = kind.titleIds
            .firstOrNull { it.equals(chosen, ignoreCase = true) }
            ?.let { File(temp, it).takeIf(File::isDirectory) }
            ?: throw IOException("The archive does not contain save data for this game")
        return unwrapEdenContentRoot(titleDir)
    }

    private fun unwrapEdenContentRoot(titleDir: File): File {
        val children = titleDir.listFiles()?.toList().orEmpty()
        val onlyZero = children.singleOrNull { it.isDirectory && it.name == "0" }
        return if (onlyZero != null && children.none(File::isFile)) onlyZero else titleDir
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

    companion object {
        const val METADATA_FILE = "ryubing-save.txt"
        val HEX_ID = Regex("[0-9a-fA-F]{16}")
    }
}
