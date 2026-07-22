package org.ryubing.android.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.ryubing.android.emu.EmulationSession
import java.io.File

/**
 * Walks a user-selected Updates/DLC folder, probes each `.nsp`/`.xci`, and merges
 * matching content into `{filesDir}/games/{titleId}/updates.json` and `dlc.json`.
 */
class ContentAutoloader(
    private val context: Context,
    private val appDataPath: String,
    private val session: EmulationSession,
) {

    /**
     * Blocking I/O + native probes — call off the main thread after [EmulationSession.initialize].
     */
    fun autoload(updatesFolderUri: String, games: List<GameEntry>) {
        if (updatesFolderUri.isBlank()) return
        val gamesByTid = games
            .mapNotNull { g ->
                val tid = g.titleId.lowercase().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                tid to g
            }
            .toMap()
        if (gamesByTid.isEmpty()) return

        val tree = DocumentFile.fromTreeUri(context, Uri.parse(updatesFolderUri)) ?: return
        val durableRoot = File(appDataPath, "content_autoload").apply { mkdirs() }

        walkFiles(tree).forEach { doc ->
            val name = doc.name ?: return@forEach
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in SUPPORTED) return@forEach

            val localPath = resolveOrCopy(doc, name, durableRoot) ?: return@forEach
            tryAssociate(localPath, name, gamesByTid)
        }
    }

    private fun tryAssociate(path: String, displayName: String, gamesByTid: Map<String, GameEntry>) {
        // Prefer DLC match against known base titles.
        for ((tid, _) in gamesByTid) {
            val json = session.getDlcContentListJson(path, displayName, tid) ?: continue
            mergeDlc(tid, path, json)
            return
        }

        val update = session.probeTitleUpdate(path, displayName) ?: return
        val baseTid = ContentMetadataStore.toBaseTitleId(update.titleId)
        if (baseTid !in gamesByTid) return

        val meta = ContentMetadataStore.loadUpdates(appDataPath, baseTid)
        if (path in meta.paths) return
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
    }

    private fun mergeDlc(titleId: String, containerPath: String, probeJson: String) {
        val existing = ContentMetadataStore.loadDlc(appDataPath, titleId)
        if (existing.any { it.path == containerPath }) return

        val probed = ContentMetadataStore.parseDlcArray(JSONArray(probeJson))
        // Probe returns containers with the path we passed; keep as-is.
        existing.addAll(probed.filter { it.path.isNotBlank() })
        ContentMetadataStore.saveDlc(appDataPath, titleId, existing)
        Log.i(TAG, "Autoloaded DLC for $titleId: $containerPath")
    }

    private fun resolveOrCopy(doc: DocumentFile, name: String, durableRoot: File): String? {
        SafPathResolver.resolve(context, doc.uri)?.let { return it }

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
    }
}
