package org.ryubing.android.data

import android.content.Context
import android.net.Uri
import org.ryubing.android.emu.EmulationSession
import java.io.File
import java.io.IOException

/**
 * Imports update/DLC packages for registration in updates.json / dlc.json.
 *
 * Prefers the real filesystem path from [SafPathResolver] so content stays in the
 * user's folder. Copies into app storage only when the URI has no resolvable path
 * (the native core needs a durable filesystem path).
 */
object ContentFileStore {
    fun importUri(
        context: Context,
        session: EmulationSession,
        uri: Uri,
        appDataPath: String,
        titleId: String,
        kind: String,
        displayName: String,
    ): String {
        SafPathResolver.resolve(context, uri)?.let { return it }
        return copyUri(session, uri, appDataPath, titleId, kind, displayName)
    }

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

    /** True when [file] lives under [directory] (e.g. a previous app-storage copy). */
    fun isInsideAppData(file: File, appDataPath: String): Boolean =
        isInside(file, File(appDataPath))

    fun deleteIfInsideAppData(path: String, appDataPath: String) {
        if (path.isBlank()) return
        val file = File(path)
        if (isInsideAppData(file, appDataPath) && file.isFile) {
            file.delete()
        }
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
