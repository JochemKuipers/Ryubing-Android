package org.ryubing.android.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import org.ryubing.android.emu.EmulationSession
import java.io.File

data class GameEntry(
    val title: String,
    val uri: Uri,
    val sizeBytes: Long,
    val titleId: String = "",
    /** Effective display version (selected update if any, else base). */
    val version: String = "",
    /** Original SAF file name (keeps extension for native load/probe). */
    val fileName: String = title,
    val updateCount: Int = 0,
    val hasSelectedUpdate: Boolean = false,
    val dlcCount: Int = 0,
)

/**
 * Tracks user-selected game folders (persisted SAF tree URIs) and enumerates the
 * loadable dumps inside them.
 */
class GameRepository(val context: Context) {

    private val prefs = context.getSharedPreferences("ryubing_games", Context.MODE_PRIVATE)
    private val metaPrefs = context.getSharedPreferences("ryubing_game_meta", Context.MODE_PRIVATE)
    private val updateVerPrefs = context.getSharedPreferences("ryubing_update_ver", Context.MODE_PRIVATE)
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

    /**
     * Library lookup for adb/smoke auto-launch (`LAUNCH_TITLE_ID` intent extra).
     * Matches base application ID so update IDs (...800) still find the base dump.
     */
    fun findByTitleId(titleId: String): GameEntry? {
        val want = ContentMetadataStore.toBaseTitleId(titleId).lowercase()
        if (want.isBlank()) return null
        return scanGames().firstOrNull {
            ContentMetadataStore.toBaseTitleId(it.titleId).lowercase() == want
        }
    }

    fun scanGames(): List<GameEntry> = folderUris().flatMap { uriString ->
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(uriString)) ?: return@flatMap emptyList()
        tree.listFiles()
            .filter { it.isFile && it.name?.substringAfterLast('.', "")?.lowercase() in supportedExtensions }
            .map { doc ->
                val fileName = doc.name ?: "Unknown"
                val cached = loadCachedMeta(doc.uri)
                GameEntry(
                    title = cached?.title?.takeIf { it.isNotBlank() } ?: fileName,
                    uri = doc.uri,
                    sizeBytes = doc.length(),
                    titleId = cached?.titleId.orEmpty(),
                    version = cached?.version.orEmpty(),
                    fileName = fileName,
                )
            }
    }.sortedBy { it.title.lowercase() }

    /**
     * Fills missing title metadata via native probe and caches results.
     * Blocking — call off the main thread after [EmulationSession.initialize].
     */
    fun enrichGames(games: List<GameEntry>, session: EmulationSession): List<GameEntry> {
        return games.map { game ->
            val titleLooksLikeFile = game.title.equals(game.fileName, ignoreCase = true)
            if (game.titleId.isNotBlank() && !titleLooksLikeFile) return@map game

            val cached = loadCachedMeta(game.uri)
            if (cached != null && cached.titleId.isNotBlank() && cached.title.isNotBlank()) {
                return@map game.copy(
                    title = cached.title,
                    titleId = cached.titleId,
                    version = cached.version.ifBlank { game.version },
                )
            }
            // Re-probe when we only have a partial cache (title ID without display name).
            enrichOne(game, session) ?: game.copy(
                titleId = cached?.titleId?.ifBlank { game.titleId } ?: game.titleId,
                version = cached?.version?.ifBlank { game.version } ?: game.version,
            )
        }
    }

    /**
     * Overlays selected update version + DLC/update counts from `{appData}/games/{id}/`.
     * Blocking if a selected update version must be probed.
     */
    fun applyContentMetadata(
        games: List<GameEntry>,
        appDataPath: String,
        session: EmulationSession?,
    ): List<GameEntry> {
        return games.map { game ->
            if (game.titleId.isBlank()) return@map game
            val updates = ContentMetadataStore.loadUpdates(appDataPath, game.titleId)
            val dlc = ContentMetadataStore.loadDlc(appDataPath, game.titleId)
            val dlcCount = dlc.sumOf { c -> c.dlcNcaList.count { it.isEnabled } }
            val hasSelected = updates.selected.isNotBlank() && File(updates.selected).exists()
            val displayVersion = if (hasSelected && session != null) {
                resolveUpdateDisplayVersion(session, updates.selected) ?: game.version
            } else {
                game.version
            }
            game.copy(
                version = displayVersion.ifBlank { game.version },
                updateCount = updates.paths.size,
                hasSelectedUpdate = hasSelected,
                dlcCount = dlcCount,
            )
        }.sortedBy { it.title.lowercase() }
    }

    private fun resolveUpdateDisplayVersion(session: EmulationSession, path: String): String? {
        updateVerPrefs.getString(path, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val info = session.probeTitleUpdate(path, File(path).name) ?: return null
        val ver = info.displayVersion.ifBlank { null } ?: return null
        updateVerPrefs.edit { putString(path, ver) }
        return ver
    }

    private fun enrichOne(game: GameEntry, session: EmulationSession): GameEntry? {
        var pfd: ParcelFileDescriptor? = null
        return try {
            pfd = context.contentResolver.openFileDescriptor(game.uri, "r") ?: return null
            val fdPath = "/proc/self/fd/${pfd.fd}"
            val info = session.queryApplicationInfo(fdPath, game.fileName) ?: return null
            if (info.titleId.isBlank()) return null
            val title = info.titleName.ifBlank { game.title }
            cacheMeta(game.uri, info.titleId, info.version, title)
            game.copy(
                title = title,
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

    private data class CachedMeta(val titleId: String, val version: String, val title: String)

    private fun loadCachedMeta(uri: Uri): CachedMeta? {
        val raw = metaPrefs.getString(uri.toString(), null) ?: return null
        val parts = raw.split('\u0001')
        val tid = parts.getOrNull(0).orEmpty()
        if (tid.isBlank()) return null
        return CachedMeta(
            titleId = tid,
            version = parts.getOrNull(1).orEmpty(),
            title = parts.getOrNull(2).orEmpty(),
        )
    }

    private fun cacheMeta(uri: Uri, titleId: String, version: String, title: String) {
        metaPrefs.edit { putString(uri.toString(), "$titleId\u0001$version\u0001$title") }
    }

    private companion object {
        const val KEY_FOLDERS = "folders"
        const val TAG = "GameRepository"
    }
}
