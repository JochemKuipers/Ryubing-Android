package org.ryubing.android.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.ryubing.android.emu.EmulationSession
import java.io.File

/**
 * Walks a user-selected Updates/DLC folder, probes each `.nsp`/`.xci`, and merges
 * matching content into `{filesDir}/games/{titleId}/updates.json` and `dlc.json`.
 *
 * Prefers real filesystem paths via [SafPathResolver]. Otherwise probes through a
 * temporary SAF fd and only copies into app storage when a match is found.
 */
class ContentAutoloader(
    private val context: Context,
    private val appDataPath: String,
    private val session: EmulationSession,
) {

    /**
     * Blocking I/O + native probes — call off the main thread after [EmulationSession.initialize].
     * @return Pair of (updatesAdded, dlcAdded).
     */
    fun autoload(updatesFolderUri: String, games: List<GameEntry>): Pair<Int, Int> {
        if (updatesFolderUri.isBlank()) return 0 to 0
        val gamesByTid = games
            .mapNotNull { g ->
                val tid = g.titleId.lowercase().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                tid to g
            }
            .toMap()
        if (gamesByTid.isEmpty()) {
            Log.w(TAG, "Autoload skipped: no games have a title ID yet")
            return 0 to 0
        }

        val tree = DocumentFile.fromTreeUri(context, Uri.parse(updatesFolderUri))
        if (tree == null) {
            Log.w(TAG, "Autoload: cannot open tree $updatesFolderUri")
            return 0 to 0
        }
        val durableRoot = File(appDataPath, "content_autoload").apply { mkdirs() }

        var updatesAdded = 0
        var dlcAdded = 0
        var scanned = 0
        walkFiles(tree).forEach { doc ->
            val name = doc.name ?: return@forEach
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in SUPPORTED) return@forEach
            scanned++

            // Fast reject when filename embeds a title ID that isn't in the library (Kenji-style).
            filenameTitleId(name)?.let { fileTid ->
                val base = ContentMetadataStore.toBaseTitleId(fileTid)
                if (!titleBelongsToLibrary(fileTid, base, gamesByTid.keys)) {
                    Log.d(TAG, "Skip $name — [$fileTid] not in library")
                    return@forEach
                }
            }

            when (processFile(doc, name, gamesByTid, durableRoot)) {
                AssociateResult.Update -> updatesAdded++
                AssociateResult.Dlc -> dlcAdded++
                AssociateResult.None -> Unit
            }
        }
        Log.i(TAG, "Autoload done: scanned=$scanned updates=$updatesAdded dlc=$dlcAdded games=${gamesByTid.size}")
        return updatesAdded to dlcAdded
    }

    private enum class AssociateResult { None, Update, Dlc }

    private fun processFile(
        doc: DocumentFile,
        name: String,
        gamesByTid: Map<String, GameEntry>,
        durableRoot: File,
    ): AssociateResult {
        SafPathResolver.resolve(context, doc.uri)?.let { real ->
            Log.d(TAG, "Resolved $name -> $real")
            val preview = classify(real, name, gamesByTid)
            if (preview == AssociateResult.None) return AssociateResult.None
            val durable = copyFileToDurable(real, name, durableRoot) ?: return AssociateResult.None
            return tryAssociate(durable, name, gamesByTid)
        }

        // Probe via fd without copying; copy only if something matches.
        var pfd: ParcelFileDescriptor? = null
        return try {
            pfd = context.contentResolver.openFileDescriptor(doc.uri, "r") ?: return AssociateResult.None
            val fdPath = "/proc/self/fd/${pfd.fd}"
            val preview = classify(fdPath, name, gamesByTid)
            if (preview == AssociateResult.None) return AssociateResult.None

            val durable = copyToDurable(doc, name, durableRoot) ?: return AssociateResult.None
            tryAssociate(durable, name, gamesByTid)
        } catch (e: Exception) {
            Log.e(TAG, "Autoload failed for $name", e)
            AssociateResult.None
        } finally {
            pfd?.let { runCatching { it.close() } }
        }
    }

    /** Probe-only: would this file associate as DLC or update? Does not write metadata. */
    private fun classify(
        path: String,
        displayName: String,
        gamesByTid: Map<String, GameEntry>,
    ): AssociateResult {
        for ((tid, _) in gamesByTid) {
            if (session.getDlcContentListJson(path, displayName, tid) != null) {
                return AssociateResult.Dlc
            }
        }
        val update = session.probeTitleUpdate(path, displayName) ?: return AssociateResult.None
        val baseTid = ContentMetadataStore.toBaseTitleId(update.titleId)
        return if (baseTid in gamesByTid) AssociateResult.Update else AssociateResult.None
    }

    private fun tryAssociate(
        path: String,
        displayName: String,
        gamesByTid: Map<String, GameEntry>,
    ): AssociateResult {
        for ((tid, _) in gamesByTid) {
            val json = session.getDlcContentListJson(path, displayName, tid) ?: continue
            return if (mergeDlc(tid, path, json)) AssociateResult.Dlc else AssociateResult.None
        }

        val update = session.probeTitleUpdate(path, displayName) ?: return AssociateResult.None
        val baseTid = ContentMetadataStore.toBaseTitleId(update.titleId)
        if (baseTid !in gamesByTid) {
            Log.d(TAG, "Update $displayName title ${update.titleId} not in library")
            return AssociateResult.None
        }

        val meta = ContentMetadataStore.loadUpdates(appDataPath, baseTid)
        if (path in meta.paths) return AssociateResult.None
        // Same content may already be registered under a different location (e.g. after
        // migration into app storage); dedupe by file name so autoload doesn't duplicate it.
        if (meta.paths.any { File(it).name.equals(File(path).name, ignoreCase = true) }) {
            return AssociateResult.None
        }
        meta.paths.add(path)
        val shouldSelect = if (meta.selected.isEmpty()) {
            true
        } else {
            val current = session.probeTitleUpdate(meta.selected, File(meta.selected).name)
            current == null || update.version > current.version
        }
        if (shouldSelect) meta.selected = path
        ContentMetadataStore.saveUpdates(appDataPath, baseTid, meta)
        Log.i(TAG, "Autoloaded update for $baseTid: $displayName (v${update.displayVersion})")
        return AssociateResult.Update
    }

    private fun mergeDlc(titleId: String, containerPath: String, probeJson: String): Boolean {
        val existing = ContentMetadataStore.loadDlc(appDataPath, titleId)
        if (existing.any { it.path == containerPath }) return false
        // Dedupe by file name as well (migration may have relocated a registered container).
        if (existing.any { File(it.path).name.equals(File(containerPath).name, ignoreCase = true) }) {
            return false
        }

        val probed = ContentMetadataStore.parseDlcArray(JSONArray(probeJson))
        existing.addAll(probed.filter { it.path.isNotBlank() })
        ContentMetadataStore.saveDlc(appDataPath, titleId, existing)
        Log.i(TAG, "Autoloaded DLC for $titleId: $containerPath")
        return true
    }

    private fun copyToDurable(doc: DocumentFile, name: String, durableRoot: File): String? {
        val safeName = name.replace(Regex("[^A-Za-z0-9._\\-]"), "_")
        val dest = File(durableRoot, safeName)
        if (dest.exists() && dest.length() == doc.length()) {
            return dest.absolutePath
        }
        return try {
            session.copyUriTo(doc.uri, dest)
            dest.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy $name for autoload", e)
            null
        }
    }

    private fun copyFileToDurable(path: String, name: String, durableRoot: File): String? {
        val source = File(path)
        val safeName = name.replace(Regex("[^A-Za-z0-9._\\-]"), "_")
        val destination = File(durableRoot, safeName)
        if (destination.isFile && destination.length() == source.length()) return destination.absolutePath
        return runCatching {
            source.copyTo(destination, overwrite = true)
            destination.absolutePath
        }.onFailure { Log.e(TAG, "Failed to copy $name for autoload", it) }.getOrNull()
    }

    private fun walkFiles(root: DocumentFile): Sequence<DocumentFile> = sequence {
        val stack = ArrayDeque<DocumentFile>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (current.isFile) {
                yield(current)
                continue
            }
            if (current.isDirectory) {
                current.listFiles().forEach { stack.add(it) }
            }
        }
    }

    private companion object {
        const val TAG = "ContentAutoloader"
        val SUPPORTED = setOf("nsp", "xci")
        private val TITLE_ID_IN_NAME = Regex("\\[([0-9a-fA-F]{16})]")

        fun filenameTitleId(name: String): String? =
            TITLE_ID_IN_NAME.find(name)?.groupValues?.get(1)?.lowercase()

        fun titleBelongsToLibrary(fileTid: String, baseTid: String, library: Set<String>): Boolean {
            if (baseTid in library || fileTid in library) return true
            val masked = fileTid.toULongOrNull(16)?.and(0xFFFFFFFFFFFFE000uL) ?: return false
            return library.any { tid ->
                tid.toULongOrNull(16)?.and(0xFFFFFFFFFFFFE000uL) == masked
            }
        }
    }
}
