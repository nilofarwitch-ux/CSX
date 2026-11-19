package com.zeefliz4k

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URI
import java.util.Base64

// ============================================================
// SHARED EXTRACTOR HELPERS
// ============================================================

fun getBaseUrl(url: String): String {
    return runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    }.getOrDefault(url.substringBefore("/", url))
}

private var cachedDomains: Map<String, String>? = null

suspend fun getLatestBaseUrl(baseUrl: String, key: String): String {
    val domains = cachedDomains ?: runCatching {
        val jsonStr = app.get(
            "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json",
            timeout = 5
        ).text
        val obj = JSONObject(jsonStr)
        buildMap {
            obj.keys().forEach { k -> put(k.lowercase(), obj.optString(k).trimEnd('/')) }
        }.also { cachedDomains = it }
    }.getOrNull()

    return domains?.get(key.lowercase())?.takeIf { it.startsWith("http") } ?: baseUrl
}

fun base64DecodeLocal(input: String): String {
    return runCatching {
        var clean = input.trim().replace('-', '+').replace('_', '/')
        val pad = clean.length % 4
        if (pad > 0) clean += "=".repeat(4 - pad)
        String(Base64.getDecoder().decode(clean), Charsets.UTF_8)
    }.getOrDefault("")
}

fun getIndexQuality(str: String?): Int {
    if (str.isNullOrBlank()) return Qualities.Unknown.value
    return when {
        str.contains("2160p", true) || str.contains("4k", true) -> Qualities.P2160.value
        str.contains("1440p", true) || str.contains("2k", true) -> Qualities.P1440.value
        str.contains("1080p", true) || str.contains("fhd", true) -> Qualities.P1080.value
        str.contains("720p", true) || str.contains("hd", true) -> Qualities.P720.value
        str.contains("480p", true) -> Qualities.P480.value
        str.contains("360p", true) -> Qualities.P360.value
        else -> Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}

// ============================================================
// EXTRACTORS
// ============================================================

class VCloud : HubCloud() {
    override val name: String = "V-Cloud"
    override val mainUrl: String = "https://vcloud.*"
}

open class HubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.*"
    override val requiresReferer = false

    fun extractPxlUrl(html: String): String? =
        Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)

    fun extractDoubleAtob(html: String): String? =
        Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""")
            .find(html)?.groupValues?.get(1)?.let { base64DecodeLocal(base64DecodeLocal(it)) }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var baseUrl = getBaseUrl(url)
        val latestBaseUrl = if (url.contains("hubcloud", true)) {
            getLatestBaseUrl(baseUrl, "hubcloud")
        } else {
            getLatestBaseUrl(baseUrl, "vcloud")
        }
        var newUrl = url
        if (baseUrl != latestBaseUrl) {
            newUrl = url.replace(baseUrl, latestBaseUrl)
            baseUrl = latestBaseUrl
        }

        val doc = runCatching { app.get(newUrl, referer = referer).document }.getOrNull() ?: return
        var link = if (newUrl.contains("/video/")) {
            doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
        } else {
            val html = doc.toString()
            val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
            extractDoubleAtob(scriptTag)
                ?: Regex("""var\s+url\s*=\s*['"]([^'"]+)['"]""").find(scriptTag)?.groupValues?.get(1)
                ?: Regex("""(?:window\.)?location(?:\.href|\.replace\s*\()\s*[=(]\s*['"]([^'"]+)['"]""")
                    .find(html)?.groupValues?.get(1)
                ?: ""
        }
        if (link.isBlank()) return
        if (!link.startsWith("http")) link = baseUrl + link

        val document = runCatching { app.get(link, referer = newUrl).document }.getOrNull() ?: return
        val header = document.select("div.card-header").text().trim()
        val size = document.select("i#size").text().trim()
        val quality = getIndexQuality(header)

        suspend fun emit(linkUrl: String, server: String = "") {
            callback.invoke(
                newExtractorLink(
                    source = "${name}${server}",
                    name = "${name}${server} $header [$size]".trim(),
                    url = linkUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                    this.referer = newUrl
                }
            )
        }

        val downloadHosts = listOf(
            "r2.cloudflarestorage.com", "storage.googleapis.com", "pixeldra",
            "mega.nz", "drive.google.com", "gofile.io", "buzzheavier", "hubcloud", "hubcdn"
        )
        val skipHosts = listOf(
            "snvhost.com", "hdhub4u", "one.one.one.one", "tinyurl.com", "t.me",
            "google.com/search", "/drive/admin"
        )

        document.select("a[href]").amap { btn ->
            runCatching {
                val linkHref = btn.attr("href").trim()
                val text = btn.text().trim()
                if (linkHref.isBlank()) return@runCatching
                if (skipHosts.any { linkHref.contains(it, true) }) return@runCatching
                if (!downloadHosts.any { linkHref.contains(it, true) }) return@runCatching

                val server = when {
                    text.contains("FSLv2", true) || linkHref.contains("/hub2/", true) -> "[FSLv2]"
                    text.contains("FSL", true) || linkHref.contains("/hub/", true) -> "[FSL]"
                    text.contains("ZipDisk", true) || linkHref.contains("storage.googleapis.com", true) -> "[ZipDisk]"
                    text.contains("Mega", true) -> "[Mega]"
                    text.contains("Buzz", true) -> "[Buzz]"
                    linkHref.contains("pixeldra", true) -> "[Pixeldrain]"
                    else -> ""
                }

                when {
                    text.contains("Gofile", true) -> loadExtractor(linkHref, newUrl, subtitleCallback, callback)
                    linkHref.contains("pixeldra", true) -> {
                        val b = getBaseUrl(linkHref)
                        val finalURL = if (linkHref.contains("download", true)) {
                            linkHref
                        } else {
                            "$b/api/file/${linkHref.substringAfterLast("/")}?download"
                        }
                        emit(finalURL, "[Pixeldrain]")
                    }
                    text.contains("Buzz", true) -> {
                        val baseBuzz = getBaseUrl(linkHref)
                        val r = runCatching {
                            app.get(linkHref, referer = newUrl).document.selectFirst(".download-btn")?.attr("href")
                        }.getOrNull().orEmpty()
                        if (r.isNotBlank()) emit(baseBuzz + r, "[Buzz]")
                    }
                    else -> emit(linkHref, server)
                }
            }
        }
    }
}

open class Hubdrive : ExtractorApi() {
    override val name = "Hubdrive"
    override val mainUrl = "https://hubdrive.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = runCatching { app.get(url, referer = referer).document }.getOrNull() ?: return
        val href = doc.selectFirst(".btn.btn-primary.btn-user.btn-success1.m-1")?.attr("href")
            ?: doc.selectFirst("a[href*='hubcloud'], a[href*='vcloud']")?.attr("href")
            ?: ""
        if (href.isNotBlank()) {
            loadExtractor(href, url, subtitleCallback, callback)
        }
    }
}

class GDLink : GDFlix() { override var mainUrl = "https://gdlink.*" }
class GDFlixApp : GDFlix() { override var mainUrl = "https://new.gdflix.*" }
class GdFlix1 : GDFlix() { override var mainUrl = "https://new1.gdflix.*" }
class GdFlix2 : GDFlix() { override var mainUrl = "https://new2.gdflix.*" }
class GDFlixNet : GDFlix() { override var mainUrl = "https://new18.gdflix.*" }

open class GDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var baseUrl = getBaseUrl(url)
        val latestBaseUrl = getLatestBaseUrl(baseUrl, "gdflix")
        var newUrl = url
        if (baseUrl != latestBaseUrl) {
            newUrl = url.replace(baseUrl, latestBaseUrl)
            baseUrl = latestBaseUrl
        }

        val document = runCatching { app.get(newUrl, referer = referer).document }.getOrNull() ?: return
        val fileName = document.select("ul > li.list-group-item:contains(Name)").text().substringAfter("Name : ").trim()
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text().substringAfter("Size : ").trim()
        val quality = getIndexQuality(fileName)

        suspend fun emit(linkUrl: String, server: String = "") {
            callback.invoke(
                newExtractorLink(
                    source = "${name}${server}",
                    name = "${name}${server} $fileName [$fileSize]".trim(),
                    url = linkUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                    this.referer = newUrl
                }
            )
        }

        document.select("div.text-center a").amap { anchor ->
            runCatching {
                val text = anchor.text()
                val link = anchor.attr("href")
                when {
                    text.contains("FSL V2", true) -> emit(link, "[FSL V2]")
                    text.contains("DIRECT DL", true) -> emit(link, "[Direct]")
                    text.contains("DIRECT SERVER", true) -> emit(link, "[Direct]")
                    text.contains("CLOUD DOWNLOAD [R2]", true) -> emit(link, "[Cloud]")
                    text.contains("GD Index", true) -> {
                        listOf(1, 2).amap { cfType ->
                            runCatching {
                                app.get("$baseUrl$link?type=$cfType").document
                                    .select("a.btn-success").amap { emit(it.attr("href"), "[CF]") }
                            }
                        }
                    }
                    text.contains("FAST CLOUD", true) -> {
                        val d = app.get(baseUrl + link).document.select("div.card-body a").attr("href")
                        if (d.isNotBlank()) emit(d, "[FAST CLOUD]")
                    }
                    link.contains("pixeldra", true) -> {
                        val b = getBaseUrl(link)
                        val finalURL = if (link.contains("download", true)) {
                            link
                        } else {
                            "$b/api/file/${link.substringAfterLast("/")}?download"
                        }
                        emit(finalURL, "[Pixeldrain]")
                    }
                    text.contains("Instant DL", true) -> {
                        val i = app.get(link, allowRedirects = false)
                            .headers["location"]?.substringAfter("url=").orEmpty()
                        if (i.isNotBlank()) emit(i, "[Instant Download]")
                    }
                    text.contains("GoFile", true) -> {
                        app.get(link).document.select(".row .row a").amap { g ->
                            val gl = g.attr("href")
                            if (gl.contains("gofile", true)) loadExtractor(gl, "", subtitleCallback, callback)
                        }
                    }
                }
            }
        }
    }
}

open class FastDlServer : ExtractorApi() {
    override val name: String = "fastdlserver"
    override var mainUrl: String = "https://fastdlserver.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val loc = runCatching { app.get(url, allowRedirects = false).headers["location"] }.getOrNull()
        if (!loc.isNullOrBlank()) loadExtractor(loc, "", subtitleCallback, callback)
    }
}

open class Hblinks : ExtractorApi() {
    override val name = "Hblinks"
    override val mainUrl = "https://hblinks.*"
    override val requiresReferer = false

    private val supportedHosts = listOf(
        "hubcloud", "hubdrive", "hubcdn",
        "vcloud", "gdflix", "gdirect"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = runCatching { app.get(url, referer = referer).document }.getOrNull() ?: return

        var links = doc.select("div#primary p > a")
            .mapNotNull { it.attr("href").takeIf { h -> h.startsWith("http") } }

        if (links.isEmpty()) {
            links = doc.select("a[href]")
                .mapNotNull { a ->
                    val h = a.attr("href").trim()
                    if (h.startsWith("http") && supportedHosts.any { h.contains(it, true) }) h else null
                }
                .distinct()
        }

        if (links.isEmpty()) {
            Log.d("Hblinks", "No extractor links on $url")
            return
        }

        links.amap { link ->
            runCatching {
                when {
                    link.contains("hubdrive", true) -> Hubdrive().getUrl(link, url, subtitleCallback, callback)
                    link.contains("hubcloud", true) -> HubCloud().getUrl(link, url, subtitleCallback, callback)
                    link.contains("vcloud", true) -> VCloud().getUrl(link, url, subtitleCallback, callback)
                    link.contains("gdflix", true) || link.contains("gdirect", true) -> GDFlix().getUrl(link, url, subtitleCallback, callback)
                    else -> loadExtractor(link, url, subtitleCallback, callback)
                }
            }
        }
    }
}

class HdStream4u : VidHidePro() {
    override var name = "HdStream4u"
    override var mainUrl = "https://hdstream4u.com"
}

class Hubstream : VidStack() {
    override var name = "Hubstream"
    override var mainUrl = "https://hubstream.art"
}

class Vidaraa : VidHidePro() {
    override var name = "Vidaraa"
    override var mainUrl = "https://vidaraa.cc"
}

class PlayDrive(
    private val label: String = "",
    private val sourceName: String = ""
) : ExtractorApi() {
    override val name = "PlayDrive"
    override val mainUrl = "https://playdrive.vip"
    override val requiresReferer = false
    private val SEAL_PREFIX = "pa!~"
    private val SEAL_KEY = "pa4u-stream-seal-v1"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fetchHeaders = mapOf(
            "Cookie" to "playarchive_adev=1",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36"
        )
        val html = runCatching {
            app.get(url, headers = fetchHeaders, referer = referer ?: mainUrl).text
        }.getOrNull() ?: return

        val json = extractPlayJson(html) ?: return
        var m3u8 = unseal(json.optJSONObject("item")?.optString("video_url") ?: "")
        if (!m3u8.startsWith("http")) {
            json.optJSONArray("streams")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val u = unseal(s.optString("m3u8_url"))
                    if (u.startsWith("http")) {
                        m3u8 = u
                        break
                    }
                }
            }
        }
        if (!m3u8.startsWith("http")) return

        var quality = 0
        json.optJSONObject("item")?.optJSONArray("qualities")?.let { arr ->
            for (i in 0 until arr.length()) {
                val q = arr.optJSONObject(i)?.optString("label") ?: continue
                val n = Regex("""(\d{3,4})[pP]""").find(q)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                if (n > quality) quality = n
            }
        }

        val playbackHeaders = mapOf(
            "User-Agent" to fetchHeaders["User-Agent"]!!,
            "Referer" to "https://playdrive.vip/",
            "Origin" to "https://playdrive.vip",
            "Accept" to "*/*"
        )
        val finalLabel = listOfNotNull(
            sourceName.takeIf { it.isNotBlank() },
            label.takeIf { it.isNotBlank() }
        ).joinToString(" • ").ifBlank { "PlayDrive" }

        callback.invoke(
            newExtractorLink(name, finalLabel, m3u8, ExtractorLinkType.M3U8) {
                this.quality = quality
                this.referer = "https://playdrive.vip/"
                this.headers = playbackHeaders
            }
        )
    }

    private fun extractPlayJson(html: String): JSONObject? {
        val marker = "window.__PA_PLAY__"
        val idx = html.indexOf(marker)
        if (idx < 0) return null
        val start = html.indexOf('{', idx)
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        var end = -1
        for (i in start until html.length) {
            val c = html[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            end = i
                            break
                        }
                    }
                }
            }
        }
        if (end < 0) return null
        return runCatching { JSONObject(html.substring(start, end + 1)) }.getOrNull()
    }

    private fun unseal(input: String): String {
        if (input.isBlank() || !input.startsWith(SEAL_PREFIX)) return input
        var b64 = input.removePrefix(SEAL_PREFIX).replace('-', '+').replace('_', '/')
        val pad = b64.length % 4
        if (pad > 0) b64 += "=".repeat(4 - pad)
        val dec = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return ""
        val sb = StringBuilder(dec.size)
        for (i in dec.indices) {
            val b = dec[i].toInt() and 0xFF
            val k = SEAL_KEY[i % SEAL_KEY.length].code
            sb.append(((b xor k) and 0xFF).toChar())
        }
        return sb.toString()
    }
}
