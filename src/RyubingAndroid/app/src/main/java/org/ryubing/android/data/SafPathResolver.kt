package org.ryubing.android.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

/**
 * Resolves a SAF document URI to a real filesystem path when possible (Kenji pattern:
 * `document/primary:…` / `document/root/…`), otherwise returns null so callers can copy.
 */
object SafPathResolver {

    fun resolve(context: Context, uri: Uri): String? {
        val segments = uri.pathSegments ?: return null
        val path = segments.joinToString("/")
        if (!path.startsWith("document/")) return null

        val relativePath = Uri.decode(path.removePrefix("document/"))
        val rootRelative = when {
            relativePath.startsWith("root/") -> relativePath.removePrefix("root/")
            relativePath.startsWith("primary:") -> relativePath.removePrefix("primary:")
            else -> return null
        }

        val bases = listOfNotNull(
            context.filesDir,
            context.getExternalFilesDir(null),
            Environment.getExternalStorageDirectory(),
        )
        for (base in bases) {
            val candidate = File(base, rootRelative)
            if (candidate.exists()) return candidate.absolutePath
        }
        return null
    }
}
