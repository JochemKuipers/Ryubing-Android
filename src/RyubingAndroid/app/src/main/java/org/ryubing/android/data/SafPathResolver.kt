package org.ryubing.android.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * Resolves a SAF document URI to a real filesystem path when possible.
 * Handles both single-document and tree-child URIs (`primary:…`, `raw:…`).
 */
object SafPathResolver {

    fun resolve(context: Context, uri: Uri): String? {
        val docId = runCatching {
            when {
                DocumentsContract.isDocumentUri(context, uri) ->
                    DocumentsContract.getDocumentId(uri)
                DocumentsContract.isTreeUri(uri) ->
                    DocumentsContract.getTreeDocumentId(uri)
                else -> null
            }
        }.getOrNull() ?: return null

        when {
            docId.startsWith("raw:") -> {
                val raw = docId.removePrefix("raw:")
                return raw.takeIf { File(it).exists() }
            }
            docId.startsWith("primary:") -> {
                val rel = docId.removePrefix("primary:").replace(':', '/')
                val file = File(Environment.getExternalStorageDirectory(), rel)
                if (file.exists()) return file.absolutePath
            }
            ':' in docId -> {
                // e.g. "XXXX-XXXX:Games/update.nsp" on removable storage
                val (volume, rel) = docId.split(':', limit = 2)
                val bases = listOfNotNull(
                    File("/storage/$volume"),
                    File("/mnt/media_rw/$volume"),
                    context.getExternalFilesDirs(null)
                        ?.mapNotNull { it?.absolutePath?.substringBefore("/Android/data") }
                        ?.map(::File)
                        ?.firstOrNull { it.name == volume || it.absolutePath.contains(volume) },
                )
                for (base in bases) {
                    val candidate = File(base, rel)
                    if (candidate.exists()) return candidate.absolutePath
                }
            }
        }

        // Legacy pathSegments fallback (Kenji-style)
        val segments = uri.pathSegments ?: return null
        val path = segments.joinToString("/")
        if (!path.contains("document/")) return null
        val relativePath = Uri.decode(path.substringAfter("document/"))
        val rootRelative = when {
            relativePath.startsWith("root/") -> relativePath.removePrefix("root/")
            relativePath.startsWith("primary:") -> relativePath.removePrefix("primary:")
            else -> return null
        }
        val bases = listOfNotNull(
            Environment.getExternalStorageDirectory(),
            context.getExternalFilesDir(null),
            context.filesDir,
        )
        for (base in bases) {
            val candidate = File(base, rootRelative)
            if (candidate.exists()) return candidate.absolutePath
        }
        return null
    }
}
