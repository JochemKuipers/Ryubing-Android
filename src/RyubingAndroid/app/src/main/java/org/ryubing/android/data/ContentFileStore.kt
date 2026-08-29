package org.ryubing.android.data

import android.net.Uri
import org.ryubing.android.emu.EmulationSession
import java.io.File
import java.io.IOException

object ContentFileStore {
    fun copyUri(
        session: EmulationSession,
        uri: Uri,
        appDataPath: String,
        titleId: String,
        kind: String,
        displayName: String,
    ): String {
        val destination = destination(appDataPath, titleId, kind, displayName)
        val partial = File(destination.parentFile, destination.name + ".part")
        try {
            session.copyUriTo(uri, partial)
            replace(partial, destination)
            return destination.absolutePath
        } finally {
            partial.delete()
        }
    }

    fun localizeRegisteredContent(appDataPath: String, titleId: String): Boolean {
        var changed = false
        val updates = ContentMetadataStore.loadUpdates(appDataPath, titleId)
        val localizedPaths = updates.paths.map { path ->
            localizeFile(path, appDataPath, titleId, "updates").also { if (it != path) changed = true }
        }.toMutableList()
        val localizedSelected = when {
            updates.selected.isBlank() -> ""
            else -> updates.paths.indexOf(updates.selected).takeIf { it >= 0 }
                ?.let(localizedPaths::get)
                ?: updates.selected
        }
        if (localizedPaths != updates.paths || localizedSelected != updates.selected) {
            ContentMetadataStore.saveUpdates(
                appDataPath,
                titleId,
                TitleUpdateMetadata(localizedSelected, localizedPaths),
            )
        }

        val dlc = ContentMetadataStore.loadDlc(appDataPath, titleId)
        dlc.forEach { container ->
            val localized = localizeFile(container.path, appDataPath, titleId, "dlc")
            if (localized != container.path) {
                container.path = localized
                changed = true
            }
        }
        if (changed) ContentMetadataStore.saveDlc(appDataPath, titleId, dlc)
        return changed
    }

    fun copyFile(
        sourcePath: String,
        appDataPath: String,
        titleId: String,
        kind: String,
        displayName: String,
    ): String {
        val source = File(sourcePath)
        if (!source.isFile) throw IOException("Content file is missing: $displayName")
        val destination = destination(appDataPath, titleId, kind, displayName)
        // ponytail: length+mtime reuse — a same-length source rewrite within the
        // destination's mtime granularity is kept stale; upgrade to hashing if that matters.
        if (destination.isFile &&
            destination.length() == source.length() &&
            destination.lastModified() >= source.lastModified()
        ) {
            return destination.absolutePath
        }
        val partial = File(destination.parentFile, destination.name + ".part")
        try {
            source.copyTo(partial, overwrite = true)
            replace(partial, destination)
            return destination.absolutePath
        } finally {
            partial.delete()
        }
    }

    private fun localizeFile(path: String, appDataPath: String, titleId: String, kind: String): String {
        if (path.isBlank()) return path
        val source = File(path)
        if (!source.isFile || isInside(source, File(appDataPath))) return path
        return copyFile(path, appDataPath, titleId, kind, source.name)
    }

    private fun destination(
        appDataPath: String,
        titleId: String,
        kind: String,
        displayName: String,
    ): File {
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._() -]"), "_")
            .take(160)
            .ifBlank { "content.nsp" }
        return File(ContentMetadataStore.gamesDir(appDataPath, titleId), "$kind/$safeName").also {
            it.parentFile?.mkdirs()
        }
    }

    private fun replace(source: File, destination: File) {
        if (destination.exists() && !destination.delete()) throw IOException("Cannot replace ${destination.name}")
        if (!source.renameTo(destination)) {
            source.copyTo(destination, overwrite = true)
            source.delete()
        }
    }

    private fun isInside(file: File, directory: File): Boolean =
        file.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())
}