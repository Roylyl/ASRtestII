package org.hotwordtest

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors

internal const val UPDATE_PERIOD = 30 * 60 * 1000L
internal const val REPO = "Roylyl/ASRtestII"
internal fun Context.updatePrefs() = getSharedPreferences("release_updates", Context.MODE_PRIVATE)

internal data class ReleaseVersion(val parts: List<BigInteger>) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        for (i in parts.indices) {
            val diff = parts[i].compareTo(other.parts[i])
            if (diff != 0) return diff
        }
        return 0
    }

    companion object {
        private val pattern = Regex("""([0-9]+)\.([0-9]+)\.([0-9]+)""")
        fun parse(name: String): ReleaseVersion? {
            val match = pattern.matchEntire(name.removePrefix("V").removePrefix("v")) ?: return null
            return ReleaseVersion(match.groupValues.drop(1).map { it.toBigInteger() })
        }

        fun tag(tag: String): ReleaseVersion? {
            if (!tag.startsWith("V") && !tag.startsWith("v")) return null
            return if (tag.length > 1 && tag[1] in '0'..'9') parse(tag) else null
        }
    }
}

internal data class UpdateRelease(val tag: String, val notes: String, val url: String, val size: Long) {
    fun json(): String = JSONObject().put("tag", tag).put("notes", notes).put("url", url).put("size", size).toString()
    companion object {
        fun decode(raw: String?) = raw?.let { JSONObject(it) }?.let {
            UpdateRelease(it.getString("tag"), it.getString("notes"), it.getString("url"), it.getLong("size"))
        }
    }
}

private fun releaseApkNames(tag: String) = listOf("ASRtestII-${tag.substring(1)}.apk", "ASRtestII.apk")

internal fun <T> selectReleaseApk(tag: String, choices: List<T>, name: (T) -> String): T? =
    releaseApkNames(tag).firstNotNullOfOrNull { expected -> choices.firstOrNull { name(it).equals(expected, true) } }
        ?: choices.singleOrNull()

// GitHub's public latest-release URL identifies the tag by redirect. HEAD never downloads an APK.
internal object PublicReleaseFallback {
    data class Head(val code: Int, val location: String?, val size: Long, val type: String?)
    private val page = URI("https://github.com/$REPO/releases/latest")
    private val redirects = setOf(301, 302, 303, 307, 308)
    private val cdnHosts = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")
    private val assetPath = Regex("""/github-production-release-asset(?:-[a-f0-9]+)?/[0-9]+/[A-Za-z0-9_-]+""")
    private val apkTypes = setOf("application/octet-stream", "binary/octet-stream", "application/vnd.android.package-archive",
        "application/zip", "application/x-zip-compressed")

    fun eligible(code: Int) = code in setOf(403, 429, 500, 502, 503, 504) || code in 520..524

    private fun redirect(from: URI, location: String?): URI? =
        location?.let { runCatching { from.resolve(it) }.getOrNull() }?.takeIf {
            it.scheme.equals("https", true) && it.host != null && it.userInfo == null && it.port == -1 && it.fragment == null
        }

    fun find(apiCode: Int, head: (URI) -> Head = ::request): Pair<UpdateRelease?, String> {
        val status = "GitHub API返回HTTP${apiCode}"
        fun probe(uri: URI): Head = try { head(uri) } catch (e: IOException) {
            throw IOException("$status，无法连接官方发行网页或附件服务", e)
        }
        val latest = probe(page)
        if (latest.code == 404) return null to "$status，官方发行页返回HTTP404，暂未找到公开版本"
        if (latest.code !in redirects) throw IOException("$status，官方发行页未提供最新标签（HTTP${latest.code}）")
        val target = redirect(page, latest.location)
        val prefix = "/$REPO/releases/tag/"
        val tag = target?.takeIf { it.host.equals("github.com", true) && it.rawQuery == null &&
            it.rawPath.startsWith(prefix, true) }?.rawPath?.substring(prefix.length)
        if (tag == null || ReleaseVersion.tag(tag) == null)
            return null to "$status，官方发行页未指向本仓库的Vx.y.z稳定版本"

        for (name in releaseApkNames(tag)) {
            val url = URI("https://github.com/$REPO/releases/download/$tag/$name")
            var asset = probe(url)
            if (asset.code in redirects) {
                val cdn = redirect(url, asset.location)
                if (cdn == null || cdn.host.lowercase() !in cdnHosts || !assetPath.matches(cdn.rawPath) ||
                    cdn.rawQuery.orEmpty().split('&').none { it.substringBefore('=').lowercase() in
                        setOf("sig", "signature", "x-amz-signature") && it.substringAfter('=', "").isNotBlank() })
                    throw IOException("$status，附件重定向未指向可信的GitHub发行资产地址")
                asset = probe(cdn)
            }
            if (asset.code == 404) continue
            if (asset.code != 200) throw IOException("$status，官方附件服务返回HTTP${asset.code}")
            if (asset.size <= 0 || asset.type?.substringBefore(';')?.trim()?.lowercase() !in apkTypes)
                throw IOException("$status，官方附件未提供可确认的APK类型和字节数")
            return UpdateRelease(tag, "", url.toString(), asset.size) to ""
        }
        return null to "$status，${tag}没有公开的ASRtestII发行APK附件（HTTP404）"
    }

    private fun request(uri: URI): Head {
        val conn = uri.toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "HEAD"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10_000
            conn.readTimeout = 12_000
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.setRequestProperty("User-Agent", "ASRtestII-update")
            return Head(conn.responseCode, conn.getHeaderField("Location"), conn.contentLengthLong, conn.contentType)
        } finally {
            conn.disconnect()
        }
    }
}

internal object ReleaseFeed {
    private const val api = "https://api.github.com/repos/$REPO/releases"
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    // All access to waiters is on the main thread, including JobService callbacks.
    private val waiters = mutableListOf<() -> Unit>()
    private var checking = false

    fun cached(context: Context) = UpdateRelease.decode(context.updatePrefs().getString("release", null))

    fun check(context: Context, force: Boolean, done: () -> Unit) {
        val prefs = context.updatePrefs()
        if (checking) {
            waiters.add(done)
            return
        }
        val age = System.currentTimeMillis() - prefs.getLong("checked", 0)
        val validFor = if (prefs.getBoolean("checkFailed", false)) 60_000L else UPDATE_PERIOD
        if (!force && age in 0 until validFor) {
            done()
            return
        }
        checking = true
        waiters.add(done)
        worker.execute {
            val edit = prefs.edit()
            try {
                val (release, message) = fetch()
                edit.putString("release", release?.json()).putString("checkMessage", message)
                    .putBoolean("checkFailed", false)
            } catch (e: Exception) {
                // Keep a previously discovered update if a later request cannot reach GitHub.
                // A definitive 404 or a release without an APK clears it via the success branch.
                edit.putString("checkMessage", "检查更新失败，${e.localizedMessage ?: "网络不可用"}")
                    .putBoolean("checkFailed", true)
            }
            edit.putLong("checked", System.currentTimeMillis()).apply()
            main.post {
                checking = false
                val callbacks = waiters.toList()
                waiters.clear()
                callbacks.forEach { it() }
            }
        }
    }

    private fun fetch(): Pair<UpdateRelease?, String> {
        var page = 1
        var newest: ReleaseVersion? = null
        var candidate: JSONObject? = null
        while (true) {
            val conn = URL("$api?per_page=30&page=$page").openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 12_000
                conn.readTimeout = 16_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "ASRtestII-update")
                when (val code = conn.responseCode) {
                    404 -> return null to "暂时无法访问公开的GitHub Releases"
                    200 -> Unit
                    else -> if (PublicReleaseFallback.eligible(code)) return PublicReleaseFallback.find(code)
                        else throw IOException("GitHub返回HTTP$code")
                }
                val entries = conn.inputStream.bufferedReader(Charsets.UTF_8).use { JSONArray(it.readText()) }
                for (i in 0 until entries.length()) {
                    val item = entries.getJSONObject(i)
                    if (item.optBoolean("draft") || item.optBoolean("prerelease")) continue
                    val version = ReleaseVersion.tag(item.optString("tag_name")) ?: continue
                    if (newest == null || version > newest) {
                        newest = version
                        candidate = item
                    }
                }
                if (!conn.getHeaderField("Link").orEmpty().contains("rel=\"next\"")) break
                page++
            } finally {
                conn.disconnect()
            }
        }
        val latest = candidate ?: return null to "尚无符合Vx.y.z格式的公开稳定版本"
        val tag = latest.getString("tag_name")
        val assets = latest.getJSONArray("assets")
        val choices = (0 until assets.length()).map { assets.getJSONObject(it) }.filter {
            it.optString("state") == "uploaded" && it.optLong("size", 0) > 0 &&
                officialAsset(tag, it.optString("name"), it.optString("browser_download_url"))
        }
        val asset = selectReleaseApk(tag, choices) { it.getString("name") }
            ?: return null to "${tag}没有可识别的官方APK附件"
        val body = latest.optString("body").takeUnless { latest.isNull("body") }.orEmpty()
        return UpdateRelease(tag, body, asset.getString("browser_download_url"), asset.getLong("size")) to ""
    }

    fun officialAsset(tag: String, name: String, url: String): Boolean {
        if (ReleaseVersion.tag(tag) == null || !name.endsWith(".apk", true) ||
            name.any { it == '/' || it == '\\' || it.isISOControl() }) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val prefix = "/$REPO/releases/download/"
        return uri.scheme.equals("https", true) && uri.host.equals("github.com", true) &&
            uri.userInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null &&
            !uri.rawPath.contains("%2f", true) && !uri.rawPath.contains("%5c", true) &&
            uri.path.startsWith(prefix, true) && uri.path.substring(prefix.length) == "$tag/$name"
    }
}
