package org.ryubing.android.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import org.ryubing.android.emu.EmulationSession

data class GameEntry(
    val title: String,
    val uri: Uri,
    val sizeBytes: Long,
    val titleId: String = "",
    val version: String = "",
)

/**
 * Tracks user-selected game folders (persisted SAF tree URIs) and enumerates the
 * loadable dumps inside them.
 */
class GameRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("ryubing_games", Context.MODE_PRIVATE)
    private val metaPrefs = context.getSharedPreferences("ryubing_game_meta", Context.MODE_PRIVATE)
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
            .map { doc ->
                val cached = loadCachedMeta(doc.uri)
                GameEntry(
                    title = doc.name ?: "Unknown",
                    uri = doc.uri,
                    sizeBytes = doc.length(),
                    titleId = cached?.first.orEmpty(),
                    version = cached?.second.orEmpty(),
                )
            }
    }.sortedBy { it.title.lowercase() }

    /**
     * Fills missing [GameEntry.titleId] / [GameEntry.version] via native probe and caches results.
     * Blocking — call off the main thread after [EmulationSession.initialize].
     */
    fun enrichGames(games: List<GameEntry>, session: EmulationSession): List<GameEntry> {
        return games.map { game ->
            if (game.titleId.isNotBlank()) return@map game
            val cached = loadCachedMeta(game.uri)
            if (cached != null && cached.first.isNotBlank()) {
                return@map game.copy(titleId = cached.first, version = cached.second)
            }
            enrichOne(game, session) ?: game
        }
    }

    private fun enrichOne(game: GameEntry, session: EmulationSession): GameEntry? {
        var pfd: ParcelFileDescriptor? = null
        return try {
            pfd = context.contentResolver.openFileDescriptor(game.uri, "r") ?: return null
            val fdPath = "/proc/self/fd/${pfd.fd}"
            val info = session.queryApplicationInfo(fdPath, game.title) ?: return null
            if (info.titleId.isBlank()) return null
            cacheMeta(game.uri, info.titleId, info.version)
            game.copy(
                title = info.titleName.ifBlank { game.title },
                titleId = info.titleId,
                version = info.version,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enrich ${game.title}", e)
            null
        } finally {
            pfd?.let { runCatching { it.close() } }
        }
    }

    private fun loadCachedMeta(uri: Uri): Pair<String, String>? {
        val raw = metaPrefs.getString(uri.toString(), null) ?: return null
        val parts = raw.split('\u0001', limit = 2)
        val tid = parts.getOrNull(0).orEmpty()
        val ver = parts.getOrNull(1).orEmpty()
        if (tid.isBlank()) return null
        return tid to ver
    }

    private fun cacheMeta(uri: Uri, titleId: String, version: String) {
        metaPrefs.edit { putString(uri.toString(), "$titleId\u0001$version") }
    }

    private companion object {
        const val KEY_FOLDERS = "folders"
        const val TAG = "GameRepository"
    }
}
