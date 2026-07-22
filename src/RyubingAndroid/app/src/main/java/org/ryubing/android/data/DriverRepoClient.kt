package org.ryubing.android.data

import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class DriverSourceRepo(
    val displayName: String,
    /** GitHub `owner/repo`. */
    val path: String,
    val useTagName: Boolean = false,
    val sortByPublishTime: Boolean = false,
)

data class DriverReleaseAsset(
    val name: String,
    val downloadUrl: String,
)

data class DriverRelease(
    val title: String,
    val tagName: String,
    val prerelease: Boolean,
    val latest: Boolean,
    val publishedAtMs: Long,
    val assets: List<DriverReleaseAsset>,
)

data class DriverRepoGroup(
    val repo: DriverSourceRepo,
    val releases: List<DriverRelease>,
    val error: String? = null,
)

/**
 * Lists and downloads Turnip/Adreno driver zips from public GitHub release repos
 * (same sources commonly used by Android Switch emulators).
 */
object DriverRepoClient {

    val DEFAULT_REPOS: List<DriverSourceRepo> = listOf(
        DriverSourceRepo("Mr. Purple Turnip", "MrPurple666/purple-turnip"),
        DriverSourceRepo("GameHub Adreno 8xx", "crueter/GameHub-8Elite-Drivers"),
        DriverSourceRepo(
            displayName = "Steven Adreno Tools",
            path = "StevenMXZ/Adreno-Tools-Drivers",
            useTagName = true,
            sortByPublishTime = true,
        ),
        DriverSourceRepo("Weab-Chan Freedreno", "Weab-chan/freedreno_turnip-CI"),
        DriverSourceRepo(
            displayName = "Whitebelyash Turnip",
            path = "whitebelyash/freedreno_turnip-CI",
            sortByPublishTime = true,
        ),
    )

    @Throws(IOException::class)
    fun fetchGroup(repo: DriverSourceRepo): DriverRepoGroup {
        return try {
            val body = httpGet("https://api.github.com/repos/${repo.path}/releases")
            val releases = parseReleases(body, repo)
            DriverRepoGroup(repo, releases)
        } catch (e: Exception) {
            DriverRepoGroup(repo, emptyList(), error = e.message ?: e.javaClass.simpleName)
        }
    }

    @Throws(IOException::class)
    fun downloadAsset(url: String, dest: File) {
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()
        val tmp = File(dest.absolutePath + ".part")
        if (tmp.exists()) tmp.delete()

        val conn = open("GET", url).apply {
            setRequestProperty("Accept", "application/octet-stream")
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("Download failed HTTP $code")
            }
            conn.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseReleases(json: String, repo: DriverSourceRepo): List<DriverRelease> {
        val array = JSONArray(json)
        val out = ArrayList<DriverRelease>(array.length())
        var markedLatest = false
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val tagName = obj.optString("tag_name")
            val name = obj.optString("name").ifBlank { tagName }
            val title = if (repo.useTagName) tagName else name
            val prerelease = obj.optBoolean("prerelease")
            val publishedAt = parsePublishedAt(obj.optString("published_at"))
            val assetsJson = obj.optJSONArray("assets") ?: JSONArray()
            val assets = ArrayList<DriverReleaseAsset>()
            for (a in 0 until assetsJson.length()) {
                val asset = assetsJson.getJSONObject(a)
                val assetName = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (assetName.isBlank() || url.isBlank()) continue
                if (!assetName.endsWith(".zip", ignoreCase = true) &&
                    !assetName.endsWith(".adpkg.zip", ignoreCase = true)
                ) {
                    continue
                }
                assets += DriverReleaseAsset(assetName, url)
            }
            if (assets.isEmpty()) continue
            val latest = !markedLatest && !prerelease
            if (latest) markedLatest = true
            out += DriverRelease(
                title = title.ifBlank { tagName },
                tagName = tagName,
                prerelease = prerelease,
                latest = latest,
                publishedAtMs = publishedAt,
                assets = assets,
            )
        }
        return if (repo.sortByPublishTime) {
            out.sortedByDescending { it.publishedAtMs }
        } else {
            out
        }
    }

    private fun parsePublishedAt(raw: String): Long {
        if (raw.isBlank()) return 0L
        return runCatching {
            java.time.Instant.parse(raw).toEpochMilli()
        }.getOrDefault(0L)
    }

    @Throws(IOException::class)
    private fun httpGet(url: String): String {
        val conn = open("GET", url).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException("GitHub HTTP $code: ${body.take(200)}")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun open(method: String, url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "Ryubing-Android")
        }
    }
}
