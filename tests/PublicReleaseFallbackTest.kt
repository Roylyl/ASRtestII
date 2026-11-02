package org.hotwordtest

import java.io.IOException
import java.net.URI

fun main() {
    val version = "V1.10.0"
    val latest = "https://github.com/$REPO/releases/latest"
    val tagPage = "/$REPO/releases/tag/$version"
    val apk = "https://github.com/$REPO/releases/download/$version/ASRtestII.apk"
    val cdn = "https://release-assets.githubusercontent.com/github-production-release-asset/1234/ab-cd?sig=signed"
    fun response(code: Int, location: String? = null, bytes: Long = -1, type: String? = null) =
        PublicReleaseFallback.Head(code, location, bytes, type)
    fun probe(vararg expected: Pair<String, PublicReleaseFallback.Head>): Pair<UpdateRelease?, String> {
        var i = 0
        return PublicReleaseFallback.find(403) { uri: URI ->
            val (url, reply) = expected[i++]
            check(uri.toString() == url)
            reply
        }.also { check(i == expected.size) }
    }
    val latestReply = latest to response(302, tagPage)
    val cdnReply = apk to response(302, cdn)
    val bytes = Int.MAX_VALUE.toLong() + 123
    val result = probe(latestReply, cdnReply, cdn to response(200, bytes = bytes, type = "application/octet-stream"))
    check(result.first == UpdateRelease(version, "", apk, bytes))
    check(probe(latestReply, apk to response(200, bytes = 210, type = "application/vnd.android.package-archive")).first?.size == 210L)
    check(probe(latest to response(404)).let { it.first == null && it.second.contains("HTTP403") && it.second.contains("HTTP404") })
    check(probe(latestReply, apk to response(404)).first == null)
    check(probe(latest to response(302, "/other/repo/releases/tag/$version")).first == null)
    check(probe(latest to response(302, "/$REPO/releases/tag/V2.0.0-beta")).first == null)
    check(probe(latest to response(302, "https://github.com.evil.example$tagPage")).first == null)
    for (bad in listOf(cdn.replace("githubusercontent.com", "githubusercontent.com.evil.example"),
        cdn.replace("https:", "http:"), cdn.substringBefore('?'), cdn.replace("github-production-release-asset", "other"))) {
        check(runCatching { probe(latestReply, apk to response(302, bad)) }.exceptionOrNull() is IOException)
    }
    for (bad in listOf(response(200, bytes = -1, type = "application/octet-stream"),
        response(200, bytes = 42, type = "text/html"), response(503))) {
        check(runCatching { probe(latestReply, apk to bad) }.exceptionOrNull() is IOException)
    }
    check(listOf(403, 429, 500, 502, 503, 504).all(PublicReleaseFallback::eligible))
    check(listOf(200, 401, 404, 501).none(PublicReleaseFallback::eligible))
    println("官方发行页回退用例通过")
}
