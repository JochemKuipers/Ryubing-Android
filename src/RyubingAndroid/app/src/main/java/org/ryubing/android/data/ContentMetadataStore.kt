package org.ryubing.android.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TitleUpdateMetadata(
    var selected: String = "",
    var paths: MutableList<String> = mutableListOf(),
)

data class DlcNcaEntry(
    var path: String = "",
    var titleId: Long = 0L,
    var isEnabled: Boolean = true,
)

data class DlcContainer(
    var path: String = "",
    var dlcNcaList: MutableList<DlcNcaEntry> = mutableListOf(),
)

/**
 * Reads/writes HLE `updates.json` / `dlc.json` under `{appData}/games/{titleId}/`
 * using snake_case keys matching Ryujinx JsonHelper.
 */
object ContentMetadataStore {

    fun gamesDir(appDataPath: String, titleId: String): File =
        File(appDataPath, "games/${titleId.lowercase()}")

    fun loadUpdates(appDataPath: String, titleId: String): TitleUpdateMetadata {
        val file = File(gamesDir(appDataPath, titleId), "updates.json")
        if (!file.exists()) return TitleUpdateMetadata()
        return runCatching {
            val obj = JSONObject(file.readText())
            val paths = mutableListOf<String>()
            val arr = obj.optJSONArray("paths")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    paths += arr.getString(i)
                }
            }
            TitleUpdateMetadata(
                selected = obj.optString("selected", ""),
                paths = paths,
            )
        }.getOrElse { TitleUpdateMetadata() }
    }

    fun saveUpdates(appDataPath: String, titleId: String, metadata: TitleUpdateMetadata) {
        val dir = gamesDir(appDataPath, titleId).apply { mkdirs() }
        val pathsArr = JSONArray()
        metadata.paths.forEach { pathsArr.put(it) }
        val obj = JSONObject()
            .put("selected", metadata.selected)
            .put("paths", pathsArr)
        File(dir, "updates.json").writeText(obj.toString())
    }

    fun loadDlc(appDataPath: String, titleId: String): MutableList<DlcContainer> {
        val file = File(gamesDir(appDataPath, titleId), "dlc.json")
        if (!file.exists()) return mutableListOf()
        return runCatching {
            parseDlcArray(JSONArray(file.readText()))
        }.getOrElse { mutableListOf() }
    }

    fun saveDlc(appDataPath: String, titleId: String, containers: List<DlcContainer>) {
        val dir = gamesDir(appDataPath, titleId).apply { mkdirs() }
        File(dir, "dlc.json").writeText(serializeDlc(containers).toString())
    }

    fun parseDlcArray(arr: JSONArray): MutableList<DlcContainer> {
        val result = mutableListOf<DlcContainer>()
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val ncas = mutableListOf<DlcNcaEntry>()
            val ncaArr = c.optJSONArray("dlc_nca_list")
            if (ncaArr != null) {
                for (j in 0 until ncaArr.length()) {
                    val n = ncaArr.getJSONObject(j)
                    ncas += DlcNcaEntry(
                        path = n.optString("path", ""),
                        titleId = n.optLong("title_id", 0L),
                        isEnabled = n.optBoolean("is_enabled", true),
                    )
                }
            }
            result += DlcContainer(
                path = c.optString("path", ""),
                dlcNcaList = ncas,
            )
        }
        return result
    }

    fun serializeDlc(containers: List<DlcContainer>): JSONArray {
        val arr = JSONArray()
        for (c in containers) {
            val ncaArr = JSONArray()
            for (n in c.dlcNcaList) {
                ncaArr.put(
                    JSONObject()
                        .put("path", n.path)
                        .put("title_id", n.titleId)
                        .put("is_enabled", n.isEnabled),
                )
            }
            arr.put(
                JSONObject()
                    .put("path", c.path)
                    .put("dlc_nca_list", ncaArr),
            )
        }
        return arr
    }

    /** Patch title IDs end in `800`; base games end in `000`. */
    fun toBaseTitleId(titleId: String): String {
        val lower = titleId.lowercase()
        return if (lower.length == 16 && lower.endsWith("800")) {
            lower.dropLast(3) + "000"
        } else {
            lower
        }
    }

    fun shouldSelectNewerUpdate(currentPath: String, newPath: String): Boolean {
        val versionPattern = Regex("\\[v(\\d+)]", RegexOption.IGNORE_CASE)
        val current = versionPattern.find(currentPath)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val newer = versionPattern.find(newPath)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return newer > current
    }
}
