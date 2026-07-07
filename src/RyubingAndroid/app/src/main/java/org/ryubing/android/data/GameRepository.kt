package org.ryubing.android.data

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile

data class GameEntry(val title: String, val uri: Uri, val sizeBytes: Long)

/**
 * Tracks user-selected game folders (persisted SAF tree URIs) and enumerates the
 * loadable dumps inside them.
 */
class GameRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("ryubing_games", Context.MODE_PRIVATE)
    private val supportedExtensions = setOf("nsp", "xci", "nca", "pfs0")

    fun addFolder(treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        val current = folderUris().toMutableSet()
        current += treeUri.toString()
        prefs.edit { putStringSet(KEY_FOLDERS, current) }
    }

    fun folderUris(): Set<String> = prefs.getStringSet(KEY_FOLDERS, emptySet()) ?: emptySet()

    fun scanGames(): List<GameEntry> = folderUris().flatMap { uriString ->
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(uriString)) ?: return@flatMap emptyList()
        tree.listFiles()
            .filter { it.isFile && it.name?.substringAfterLast('.', "")?.lowercase() in supportedExtensions }
            .map { GameEntry(it.name ?: "Unknown", it.uri, it.length()) }
    }.sortedBy { it.title.lowercase() }

    private companion object {
        const val KEY_FOLDERS = "folders"
    }
}
