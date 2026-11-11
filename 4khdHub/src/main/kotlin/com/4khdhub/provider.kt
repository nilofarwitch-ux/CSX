@file:Suppress("DEPRECATION","DEPRECATION_ERROR","UNUSED_PARAMETER","UNUSED_VARIABLE","unused")

package com.fourkhdhub

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

// ============================================================
// DATA CLASSES
// ============================================================
data class CinemetaSearchResponse(val metas: List<CinemetaSearchMeta>? = null)
data class CinemetaSearchMeta(val id: String? = null, val type: String? = null, val name: String? = null, val releaseInfo: String? = null)

data class EpisodeLink(val url: String = "", val label: String = "")

data class Meta(
    val id: String? = null, val imdb_id: String? = null, val type: String? = null,
    val poster: String? = null, val background: String? = null, val moviedb_id: Int? = null,
    val name: String? = null, val description: String? = null, val genre: List<String>? = null,
    val genres: List<String>? = null, val releaseInfo: String? = null, val status: String? = null,
    val runtime: String? = null, val cast: List<String>? = null, val language: String? = null,
    val country: String? = null, val imdbRating: String? = null, val year: String? = null,
    val videos: List<EpisodeDetails>? = null
)

data class EpisodeDetails(
    val id: String? = null, val name: String? = null, val title: String? = null,
    val season: Int = 0, val episode: Int = 0, val released: String? = null,
    val firstAired: String? = null, val overview: String? = null, val thumbnail: String? = null,
    val moviedb_id: Int? = null, val imdb_id: String? = null,
    val imdbSeason: Int? = null, val imdbEpisode: Int? = null
)

data class ResponseData(val meta: Meta? = null)

data class FourKEpisodeFile(
    val season: Int, val episode: Int, val quality: String,
    val size: String, val filename: String, val links: List<EpisodeLink>
)

// ============================================================
// HELPERS
// ============================================================
fun base64DecodeLocal(input: String): String {
    if (input.isBlank()) return ""
    val padded = input.padEnd((input.length + 3) / 4 * 4, '=')
    return try {
        String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT), Charsets.UTF_8)
    } catch (e: Exception) {
        try {
            String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT or android.util.Base64.URL_SAFE), Charsets.UTF_8)
        } catch (e2: Exception) { "" }
    }
}

fun rot13(value: String): String = value.map {
    when (it) {
        in 'A'..'Z' -> 'A' + (it - 'A' + 13) % 26
        in 'a'..'z' -> 'a' + (it - 'a' + 13) % 26
        else -> it
    }
}.joinToString("")

fun getBaseUrl(url: String): String = try {
    URI(url).let { "${it.scheme}://${it.host}" }
} catch (e: Exception) { url }

fun getIndexQuality(str: String?): Int {
    if (str.isNullOrBlank()) return Qualities.Unknown.value
    Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    val l = str.lowercase()
    return when {
        l.contains("8k") -> 4320; l.contains("4k") -> 2160; l.contains("2k") -> 1440
        else -> Qualities.Unknown.value
    }
}

suspend fun getRedirectLinks(url: String, referer: String? = null): String {
    return try {
        val html = app.get(url, referer = referer).text
        val regex = """s\('o','([A-Za-z0-9+/=]+)'|ck\('_wp_http_\d+','([^']+)'""".toRegex()
        val combined = regex.findAll(html).mapNotNull { it.groups[1]?.value ?: it.groups[2]?.value }.joinToString("")
        if (combined.isEmpty()) return ""
        val decoded = base64DecodeLocal(rot13(base64DecodeLocal(base64DecodeLocal(combined))))
        if (decoded.isBlank()) return ""
        val json = JSONObject(decoded)
        val oField = json.optString("o", "").trim()
        if (oField.isNotEmpty()) {
            val decodedUrl = base64DecodeLocal(oField).trim()
            if (decodedUrl.startsWith("http")) return decodedUrl
        }
        val data = json.optString("data", "").trim()
        val blogUrl = json.optString("blog_url", "").trim()
        if (blogUrl.isNotEmpty() && data.isNotEmpty()) {
            runCatching { app.get("$blogUrl?re=$data", referer = referer).document.select("body").text().trim() }
                .getOrDefault("").takeIf { it.startsWith("http") }?.let { return it }
        }
        ""
    } catch (e: Exception) { "" }
}

object HDHubUrlCache {
    private var cache: Map<String, String>? = null
    private var lastFetch = 0L
    private val TTL = 30 * 60 * 1000L
    private val lock = Any()
    suspend fun get(source: String, fallback: String): String {
        val now = System.currentTimeMillis()
        synchronized(lock) { cache?.let { if (now - lastFetch <= TTL) return it[source]?.takeIf { v -> v.isNotBlank() } ?: fallback } }
        val fetched = try {
            app.get("https://raw.githubusercontent.com/Faisal0786/test/main/urls.json").parsedSafe<Map<String, String>>() ?: emptyMap()
        } catch (e: Exception) { emptyMap() }
        synchronized(lock) {
            if (fetched.isNotEmpty()) { cache = fetched; lastFetch = now; return fetched[source]?.takeIf { it.isNotBlank() } ?: fallback }
            return cache?.get(source)?.takeIf { it.isNotBlank() } ?: fallback
        }
    }
}
suspend fun getLatestBaseUrl(baseUrl: String, source: String): String = HDHubUrlCache.get(source, baseUrl)

object FourKHDHubDuplicateTracker {
    private var lastReset = System.currentTimeMillis()
    private val seenKeys = object : LinkedHashMap<String, Boolean>(200, 0.7f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Boolean>?) = size > 400
    }
    private fun normalize(t: String) = t.lowercase().replace(Regex("[^a-z0-9]+"), "").take(60)
    @Synchronized fun isDuplicate(title: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastReset > 2 * 60 * 60 * 1000) { seenKeys.clear(); lastReset = now }
        val key = normalize(title); if (key.isBlank()) return false
        if (seenKeys.containsKey(key)) return true
        seenKeys[key] = true; return false
    }
}

// ============================================================
// PROVIDER
// ============================================================
class FourKHDHubProvider : MainAPI() {
    override var mainUrl = "https://4khdhub.one"
    override var name = "4KHDHub"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    init { runBlocking { baseMainUrl?.let { mainUrl = it } } }

    companion object {
        private const val URLS_JSON = "https://raw.githubusercontent.com/Faisal0786/test/main/urls.json"
        val baseMainUrl: String? by lazy {
            runBlocking {
                try {
                    val r = app.get(URLS_JSON)
                    val u = JSONObject(r.text).optString("4khdhub", "").trim().trimEnd('/')
                    u.takeIf { it.startsWith("http") }
                } catch (e: Exception) { null }
            }
        }
    }

    // 🔥 FIXED: Better title cleaning + proper Cinemeta search
    private suspend fun fetchCinemetaId(rawTitle: String, isSeries: Boolean, targetYear: Int?): String? {
        val type = if (isSeries) "series" else "movie"
        
        // Aggressive cleaning: remove ALL junk (year, quality, language, season markers, trailing numbers)
        val cleanTitle = rawTitle
            .replace(Regex("""\s*\(\d{4}\)\s*"""), " ")
            .replace(Regex("""(?i)\bSeason\s*\d+\b"""), " ")
            .replace(Regex("""(?i)\bS\d+\b"""), " ")
            .replace(Regex("""(?i)\bEpisodes?\s*\d+(?:\s*-\s*\d+)?\b"""), " ")
            .replace(Regex("""(?i)\b(Complete|Series|Web Series|Download|Watch Online|Free)\b"""), " ")
            .replace(Regex("""(?i)\b(Hindi|Tamil|Telugu|English|Dual Audio|Multi Audio|Dubbed|Dub)\b"""), " ")
            .replace(Regex("""(?i)\b(480p|720p|1080p|2160p|4k|8k|hdr|hevc|10bit|web-dl|webrip|bluray|hdtv|hdcam|predvd|x264|x265|h264|h265)\b"""), " ")
            .replace(Regex("""[\.\-_]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s*\b\d+\b\s*$"""), "") // remove trailing standalone number
            .trim()

        if (cleanTitle.isBlank()) return null
        val query = URLEncoder.encode(cleanTitle, "UTF-8")
        val url = "https://v3-cinemeta.strem.io/catalog/$type/top/search=$query.json"
        Log.d("4KHDHub", "Cinemeta search: $url")

        return try {
            val json = app.get(url).text
            val metas = tryParseJson<CinemetaSearchResponse>(json)?.metas
            if (metas.isNullOrEmpty()) {
                Log.d("4KHDHub", "Cinemeta search: no metas")
                return null
            }
            val bestMatch = if (targetYear != null) {
                metas.find { meta ->
                    val metaYear = meta.releaseInfo?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }
                    metaYear == targetYear
                } ?: metas.firstOrNull()
            } else {
                metas.firstOrNull()
            }
            Log.d("4KHDHub", "Cinemeta matched: ${bestMatch?.name} (${bestMatch?.id})")
            bestMatch?.id
        } catch (e: Exception) {
            Log.d("4KHDHub", "Cinemeta search error: ${e.message}")
            null
        }
    }

    // 🔥 FIXED: Extract episode title from filename when Cinemeta fails
    // "VisionQuest.S01E08.Forever.2160p.JHS.WEB-DL..." → "Forever"
    private fun extractEpisodeTitleFromFilename(filename: String, epNum: Int): String? {
        val junkWords = setOf(
            "web", "dl", "webrip", "web-dl", "bluray", "hdrip", "hdtv",
            "x264", "x265", "h264", "h265", "hevc", "10bit", "8bit",
            "aac", "ac3", "ddp", "atmos", "multi", "dual", "hindi",
            "english", "tamil", "telugu", "jhs", "nf", "amzn", "dsnp"
        )
        val parts = filename.split(Regex("""[\.\-_ ]+"""))
        val epPattern = Regex("""^S\d{1,2}E\d{1,3}$""", RegexOption.IGNORE_CASE)
        
        // Find the SxxExx marker index
        val epIdx = parts.indexOfFirst { epPattern.matches(it) }
        if (epIdx < 0 || epIdx + 1 >= parts.size) return null

        // Collect words AFTER the episode marker that are meaningful (skip quality/junk)
        val titleParts = mutableListOf<String>()
        for (i in (epIdx + 1) until parts.size) {
            val p = parts[i]
            if (p.isBlank()) continue
            // Skip if junk word or quality/codec
            if (p.lowercase() in junkWords) break
            if (Regex("""^\d{3,4}p$""", RegexOption.IGNORE_CASE).matches(p)) break
            if (Regex("""^\d+$""").matches(p) && titleParts.isNotEmpty()) break
            titleParts.add(p)
            if (titleParts.size >= 4) break // limit to 4 words
        }

        return titleParts.joinToString(" ").takeIf { it.isNotBlank() }
    }

    // 🔥 FIXED: Episode name resolution — tries Cinemeta, then filename, then fallback
    private fun resolveEpisodeName(
        epInfo: EpisodeDetails?, filename: String, epNum: Int
    ): String {
        // 1. Try Cinemeta name (skip if it's just "Episode N")
        val cinName = epInfo?.name?.takeIf {
            it.isNotBlank() && !Regex("""(?i)^episode\s*\d+$""").matches(it.trim())
        }
        if (cinName != null) return cinName

        // 2. Try Cinemeta title
        val cinTitle = epInfo?.title?.takeIf {
            it.isNotBlank() && !Regex("""(?i)^episode\s*\d+$""").matches(it.trim())
        }
        if (cinTitle != null) return cinTitle

        // 3. Try extract from filename (e.g. "Forever")
        val fromFile = extractEpisodeTitleFromFilename(filename, epNum)
        if (fromFile != null) return "Episode $epNum: $fromFile"

        // 4. Fallback
        return "Episode $epNum"
    }

    override val mainPage: List<MainPageData> get() = mainPageOf(
        "$mainUrl/page/%d/" to "Latest", "$mainUrl/category/movies/page/%d/" to "Movies",
        "$mainUrl/category/hindi-movies/page/%d/" to "Hindi Movies", "$mainUrl/category/series/page/%d/" to "Web Series",
        "$mainUrl/category/hindi-series/page/%d/" to "Hindi Series", "$mainUrl/category/korean-series/page/%d/" to "Korean",
        "$mainUrl/category/anime/page/%d/" to "Anime", "$mainUrl/category/2160p-HDR/page/%d/" to "4K HDR",
        "$mainUrl/category/imdb/page/%d/" to "Top IMDb"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data.format(page), referer = mainUrl).document
        val items = doc.select("a.movie-card").mapNotNull { it.toSearchResult() }.filter { !FourKHDHubDuplicateTracker.isDuplicate(it.name) }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) "$mainUrl/?s=$q" else "$mainUrl/?s=$q&page=$page"
        val doc = runCatching { app.get(url, referer = mainUrl).document }.getOrNull() ?: return null
        var items = doc.select(".card-grid > a").mapNotNull { it.toSearchResult() }
        if (items.isEmpty()) items = doc.select("a.movie-card").mapNotNull { it.toSearchResult() }
        return if (items.isNotEmpty()) newSearchResponseList(items.distinctBy { it.url }) else null
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href").trim().ifBlank { return null }
        val img = selectFirst(".movie-card-image img") ?: selectFirst("img")
        val title = selectFirst(".movie-card-title")?.text()?.trim() ?: img?.attr("alt")?.trim() ?: return null
        val poster = img?.attr("src").orEmpty()
        val metaText = selectFirst(".movie-card-meta")?.text()?.trim().orEmpty()
        val hasSeriesPattern = Regex("""\bS\d+(?:\.\d+)?\b""", RegexOption.IGNORE_CASE).containsMatchIn(metaText) || 
                               Regex("""\bEP\d+""", RegexOption.IGNORE_CASE).containsMatchIn(metaText)
        val isSeries = href.contains("-series-", true) || hasSeriesPattern
        val type = when {
            href.contains("anime", true) -> TvType.Anime
            isSeries -> TvType.TvSeries
            else -> TvType.Movie
        }
        return newMovieSearchResponse(title, fixUrl(href), type) { this.posterUrl = fixUrl(poster) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(fixUrl(url), referer = mainUrl).document }.getOrNull() ?: return null
        val rawTitle = doc.selectFirst("h1")?.text()?.trim() ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: return null
        val rawPoster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: doc.selectFirst("article img")?.attr("src") ?: ""
        val rawPlot = doc.selectFirst(".entry-content p, .post-content p, article p")?.text()?.trim() ?: ""
        
        var year: Int? = Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(rawTitle)?.value?.toIntOrNull()

        val hasSeriesPattern = doc.select(".episode-file-title").any {
            Regex("""S\d{1,2}E\d{1,3}""", RegexOption.IGNORE_CASE).containsMatchIn(it.text())
        }
        val isSeries = url.contains("-series-", true) || hasSeriesPattern

        // 1. Try website IMDb link
        var imdbUrl = doc.selectFirst("a[href*=\"imdb.com/title/\"]")?.attr("href")
        var imdbId = imdbUrl?.substringAfter("title/", "")?.substringBefore("/")?.takeIf { it.isNotBlank() }

        // 2. Fallback: Cinemeta search
        if (imdbId == null) {
            imdbId = fetchCinemetaId(rawTitle, isSeries, year)
        }

        var finalTitle = rawTitle
        var finalPlot = rawPlot
        var finalPoster = rawPoster
        var finalBackdrop = rawPoster
        var score: Double? = null
        var tags: List<String> = emptyList()
        var cast: List<String> = emptyList()
        var episodesMeta: List<EpisodeDetails> = emptyList()

        if (imdbId != null) {
            val type = if (isSeries) "series" else "movie"
            val json = runCatching { app.get("https://v3-cinemeta.strem.io/meta/$type/$imdbId.json").text }.getOrNull()
            
            tryParseJson<ResponseData>(json ?: "")?.meta?.let { meta ->
                finalTitle = meta.name?.takeIf { it.isNotBlank() } ?: finalTitle
                finalPlot = meta.description ?: finalPlot
                finalPoster = meta.poster?.takeIf { it.isNotBlank() } ?: finalPoster
                finalBackdrop = meta.background?.takeIf { it.isNotBlank() } ?: meta.poster ?: finalBackdrop
                year = meta.year?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() } ?: year
                score = meta.imdbRating?.toDoubleOrNull()
                tags = meta.genre ?: meta.genres ?: emptyList()
                cast = meta.cast ?: emptyList()
                episodesMeta = meta.videos ?: emptyList()
            }
        }
        if (finalTitle == rawTitle) finalTitle = finalTitle.replace(Regex("""\s*\(\d{4}\)\s*$"""), "").trim()

        if (isSeries) {
            val allFiles = mutableListOf<FourKEpisodeFile>()
            doc.select(".episode-download-item").forEach { item ->
                val filename = item.selectFirst(".episode-file-title")?.text()?.trim() ?: return@forEach
                val epBadge = item.selectFirst(".badge-psa")?.text()?.trim() ?: ""
                val sizeBadge = item.selectFirst(".badge-size")?.text()?.trim() ?: ""
                val seMatch = Regex("""S(\d{1,2})E(\d{1,3})""", RegexOption.IGNORE_CASE).find(filename)
                val season = seMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                val episode = seMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: Regex("""\d+""").find(epBadge)?.value?.toIntOrNull() ?: return@forEach
                val quality = Regex("""\b(2160p|1080p|720p|480p|4K|DS4K)\b""", RegexOption.IGNORE_CASE).find(filename)?.groupValues?.getOrNull(1) ?: ""
                
                val links = item.select(".episode-links a[href]").mapNotNull { a ->
                    val href = a.attr("href").trim(); val text = a.text().trim()
                    if (href.isBlank() || !href.startsWith("http")) return@mapNotNull null
                    val label = when {
                        text.contains("HubCloud", true) -> "HubCloud"
                        text.contains("HubDrive", true) -> "HubDrive"
                        text.contains("GDFlix", true) -> "GDFlix"
                        text.contains("Download", true) -> "Download"
                        text.isNotBlank() -> text; else -> "Link"
                    }
                    val finalLabel = buildString {
                        if (quality.isNotBlank()) append("$quality ")
                        append(label); if (sizeBadge.isNotBlank()) append(" [$sizeBadge]")
                    }.trim()
                    EpisodeLink(fixUrl(href), finalLabel)
                }
                if (links.isNotEmpty()) allFiles.add(FourKEpisodeFile(season, episode, quality, sizeBadge, filename, links))
            }

            if (allFiles.isNotEmpty()) {
                val grouped = allFiles.groupBy { it.season to it.episode }
                val episodes = grouped.toSortedMap(compareBy({ it.first }, { it.second })).map { (key, files) ->
                    val (season, epNum) = key
                    val epInfo = episodesMeta.find { it.season == season && it.episode == epNum }
                    val filename = files.firstOrNull()?.filename ?: ""
                    
                    // 🔥 Episode name via resolveEpisodeName (Cinemeta → filename → fallback)
                    val resolvedName = resolveEpisodeName(epInfo, filename, epNum)
                    
                    newEpisode(files.flatMap { it.links }.distinctBy { it.url }) {
                        this.name = resolvedName
                        this.season = season; this.episode = epNum
                        this.posterUrl = epInfo?.thumbnail?.takeIf { it.isNotBlank() } ?: finalPoster
                        this.description = epInfo?.overview?.takeIf { it.isNotBlank() }
                    }
                }
                return newTvSeriesLoadResponse(finalTitle, url, TvType.TvSeries, episodes) {
                    this.posterUrl = finalPoster; this.plot = finalPlot; this.tags = tags
                    this.year = year; this.score = score?.let { Score.from10(it) }
                    this.backgroundPosterUrl = finalBackdrop
                    addActors(cast); if (!imdbId.isNullOrBlank()) addImdbUrl("https://www.imdb.com/title/$imdbId/")
                }
            }
        }

        val movieLinks = mutableListOf<EpisodeLink>()
        movieLinks.addAll(extractPlayerSources(doc.toString()))
        doc.select("div.download-item").forEach { item ->
            val filename = item.selectFirst(".episode-file-title, .file-title")?.text()?.trim() ?: ""
            val sizeBadge = item.selectFirst(".badge-size")?.text()?.trim() ?: ""
            val quality = Regex("""\b(2160p|1080p|720p|480p|4K|DS4K|REMUX)\b""", RegexOption.IGNORE_CASE).find(filename)?.groupValues?.getOrNull(1) ?: ""
            
            item.select("a[href]").forEach { a ->
                val href = a.attr("href").trim(); val text = a.text().replace('\u00A0', ' ').trim()
                if (href.isBlank() || !href.startsWith("http")) return@forEach
                val host = when {
                    text.contains("HubCloud", true) -> "HubCloud"
                    text.contains("HubDrive", true) -> "HubDrive"
                    text.contains("GDFlix", true) -> "GDFlix"
                    text.contains("VCloud", true) -> "VCloud"
                    text.contains("Download", true) -> "Download"
                    text.isNotBlank() -> text; else -> "Link"
                }
                val label = buildString {
                    if (quality.isNotBlank()) append("$quality ")
                    append(host); if (sizeBadge.isNotBlank()) append(" [$sizeBadge]")
                }.trim()
                movieLinks.add(EpisodeLink(fixUrl(href), label))
            }
        }
        if (movieLinks.isEmpty()) {
            doc.select("a[href]").forEach { a ->
                val href = a.attr("href").trim(); val text = a.text().replace('\u00A0', ' ').trim()
                if (href.isBlank() || !href.startsWith("http")) return@forEach
                val isHost = text.contains("HubCloud", true) || text.contains("HubDrive", true) || text.contains("GDFlix", true) || text.contains("Download", true)
                if (!isHost) return@forEach
                var quality = ""; var size = ""
                var cur: Element? = a
                repeat(6) {
                    cur = cur?.parent() ?: return@repeat
                    val ptext = cur!!.text().replace('\u00A0', ' ')
                    if (quality.isBlank()) quality = Regex("""\b(2160p|1080p|720p|480p|4K|DS4K|REMUX)\b""", RegexOption.IGNORE_CASE).find(ptext)?.groupValues?.getOrNull(1) ?: ""
                    if (size.isBlank()) size = Regex("""\d+(?:\.\d+)?\s*(?:GB|MB)""", RegexOption.IGNORE_CASE).find(ptext)?.value ?: ""
                }
                val host = when {
                    text.contains("HubCloud", true) -> "HubCloud"
                    text.contains("HubDrive", true) -> "HubDrive"
                    text.contains("GDFlix", true) -> "GDFlix"
                    else -> "Download"
                }
                val label = buildString {
                    if (quality.isNotBlank()) append("$quality ")
                    append(host); if (size.isNotBlank()) append(" [$size]")
                }.trim()
                movieLinks.add(EpisodeLink(fixUrl(href), label))
            }
        }

        return newMovieLoadResponse(finalTitle, url, TvType.Movie, movieLinks.distinctBy { it.url }) {
            this.posterUrl = finalPoster; this.plot = finalPlot; this.tags = tags
            this.year = year; this.score = score?.let { Score.from10(it) }
            this.backgroundPosterUrl = finalBackdrop
            addActors(cast); if (!imdbId.isNullOrBlank()) addImdbUrl("https://www.imdb.com/title/$imdbId/")
        }
    }

    private fun extractPlayerSources(html: String): List<EpisodeLink> {
        val results = mutableListOf<EpisodeLink>()
        val videoId = Regex("""(?:var|let|const)\s+defaultVideoId\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return results
        val sourcesObj = extractBalancedObject(html, "videoSources") ?: return results
        Regex("""['"]?([^'":,{}\s]+)['"]?\s*:\s*['"]([^'"]+)['"]""").findAll(sourcesObj).forEach { m ->
            val quality = m.groupValues[1].trim(); val baseUrl = m.groupValues[2].trim()
            if (baseUrl.isBlank() || baseUrl.contains("function", true)) return@forEach
            val combined = baseUrl + videoId
            val finalUrl = when {
                combined.startsWith("http") -> combined; combined.startsWith("//") -> "https:$combined"; else -> "https://$combined"
            }
            results.add(EpisodeLink(finalUrl, "Player $quality"))
        }
        return results.distinctBy { it.url }
    }

    private fun extractBalancedObject(html: String, varName: String): String? {
        val idx = html.indexOf(varName); if (idx < 0) return null
        val start = html.indexOf('{', idx); if (start < 0) return null
        var depth = 0; var inStr = false; var q = ' '; var esc = false
        for (i in start until html.length) {
            val c = html[i]
            if (inStr) { when { esc -> esc = false; c == '\\' -> esc = true; c == q -> inStr = false } }
            else { when (c) { '"', '\'' -> { inStr = true; q = c }; '{' -> depth++; '}' -> { depth--; if (depth == 0) return html.substring(start, i + 1) } } }
        }
        return null
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val links = tryParseJson<List<EpisodeLink>>(data) ?: return false
        links.amap { link ->
            var u = link.url
            if (u.contains("greenmotors", true) || u.contains("greenmountmotors", true) || u.contains("hubcdn", true)) {
                val resolved = getRedirectLinks(u, mainUrl)
                if (resolved.isBlank() || resolved.contains("greenmotors", true) || resolved.contains("greenmountmotors", true) || resolved.contains("hubcdn", true)) {
                    Log.d("4KHDHub", "skip: ${u.take(50)}"); return@amap
                }
                u = resolved
            }
            when {
                link.label.startsWith("Player") || u.contains(".m3u8", true) || u.contains(".mp4", true) || u.contains("/player/", true) || u.contains("/embed/", true) -> {
                    val type = if (u.contains("m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback.invoke(newExtractorLink("4KHDHub Player", link.label, u, type) {
                        this.referer = mainUrl; this.quality = getIndexQuality(link.label)
                        this.headers = mapOf("Referer" to mainUrl, "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36")
                    })
                }
                u.contains("playdrive", true) -> PlayDrive(link.label, "4KHDHub").getUrl(u, mainUrl, subtitleCallback, callback)
                u.contains("hubdrive", true) -> Hubdrive().getUrl(u, mainUrl, subtitleCallback, callback)
                u.contains("hubcloud", true) -> HubCloud().getUrl(u, mainUrl, subtitleCallback, callback)
                u.contains("vcloud", true) -> VCloud().getUrl(u, mainUrl, subtitleCallback, callback)
                u.contains("fastdlserver", true) -> FastDlServer().getUrl(u, mainUrl, subtitleCallback, callback)
                u.contains("gdflix", true) || u.contains("gdirect", true) -> GDFlix().getUrl(u, mainUrl, subtitleCallback, callback)
                else -> Log.d("4KHDHub", "Skipping: $u")
            }
        }
        return true
    }
}

// ============================================================
// EXTRACTORS (No changes)
// ============================================================
class VCloud : HubCloud() {
    override val name: String = "V-Cloud"
    override val mainUrl: String = "https://vcloud.*"
}

open class HubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"; override val mainUrl: String = "https://hubcloud.*"; override val requiresReferer = false
    fun extractPxlUrl(html: String): String? = Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
    fun extractDoubleAtob(html: String): String? = Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""").find(html)?.groupValues?.get(1)?.let { base64DecodeLocal(base64DecodeLocal(it)) }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        var baseUrl = getBaseUrl(url)
        val latest = if (url.contains("hubcloud")) getLatestBaseUrl(baseUrl, "hubcloud") else getLatestBaseUrl(baseUrl, "vcloud")
        var newUrl = url; if (baseUrl != latest) { newUrl = url.replace(baseUrl, latest); baseUrl = latest }
        val doc = runCatching { app.get(newUrl, referer = referer).document }.getOrNull() ?: return
        var link = if (newUrl.contains("/video/")) doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
        else {
            val html = doc.toString(); val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
            extractDoubleAtob(scriptTag) ?: Regex("""var\s+url\s*=\s*['"]([^'"]+)['"]""").find(scriptTag)?.groupValues?.get(1)
                ?: Regex("""(?:window\.)?location(?:\.href|\.replace\s*\()\s*[=(]\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: ""
        }
        if (link.isBlank()) return
        if (!link.startsWith("http")) link = baseUrl + link
        val document = runCatching { app.get(link, referer = newUrl).document }.getOrNull() ?: return
        val header = document.select("div.card-header").text(); val size = document.select("i#size").text(); val quality = getIndexQuality(header)
        suspend fun emit(u: String, s: String = "") {
            callback.invoke(newExtractorLink("${name}${s}", "${name}${s} ${header}[${size}]".trim(), u, ExtractorLinkType.VIDEO) { this.quality = quality; this.referer = newUrl })
        }
        val dlHosts = listOf("r2.cloudflarestorage.com","storage.googleapis.com","pixeldra","mega.nz","drive.google.com","gofile.io","buzzheavier","hubcloud","hubcdn")
        val skipHosts = listOf("snvhost.com","hdhub4u","one.one.one.one","tinyurl.com","t.me","google.com/search","/drive/admin")
        document.select("a[href]").amap { btn ->
            val h = btn.attr("href").trim(); val t = btn.text().trim()
            if (h.isBlank() || skipHosts.any { h.contains(it, true) }) return@amap
            if (!dlHosts.any { h.contains(it, true) }) return@amap
            val srv = when {
                t.contains("FSLv2", true) || h.contains("/hub2/", true) -> "[FSLv2]"
                t.contains("FSL", true) || h.contains("/hub/", true) -> "[FSL]"
                t.contains("ZipDisk", true) || h.contains("storage.googleapis.com", true) -> "[ZipDisk]"
                t.contains("Mega", true) -> "[Mega]"; t.contains("Buzz", true) -> "[Buzz]"
                h.contains("pixeldra", true) -> "[Pixeldrain]"; else -> ""
            }
            when {
                t.contains("Gofile", true) -> loadExtractor(h, newUrl, subtitleCallback, callback)
                h.contains("pixeldra", true) -> {
                    val b = getBaseUrl(h); val f = if (h.contains("download", true)) h else "$b/api/file/${h.substringAfterLast("/")}?download"; emit(f, "[Pixeldrain]")
                }
                t.contains("Buzz", true) -> {
                    val b = getBaseUrl(h); val r = runCatching { app.get(h, referer = newUrl).document.selectFirst(".download-btn")?.attr("href") }.getOrNull().orEmpty(); if (r.isNotBlank()) emit(b + r, "[Buzz]")
                }
                else -> emit(h, srv)
            }
        }
    }
}

open class Hubdrive : ExtractorApi() {
    override val name = "Hubdrive"; override val mainUrl = "https://hubdrive.*"; override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val doc = runCatching { app.get(url, referer = referer).document }.getOrNull() ?: return
        val href = doc.selectFirst(".btn.btn-primary.btn-user.btn-success1.m-1")?.attr("href") ?: doc.selectFirst("a[href*='hubcloud']")?.attr("href") ?: ""
        if (href.isNotBlank()) loadExtractor(href, "", subtitleCallback, callback)
    }
}

class GDLink : GDFlix() { override var mainUrl = "https://gdlink.*" }
class GDFlixApp : GDFlix() { override var mainUrl = "https://new.gdflix.*" }
class GdFlix1 : GDFlix() { override var mainUrl = "https://new1.gdflix.*" }
class GdFlix2 : GDFlix() { override var mainUrl = "https://*.gdflix.*" }
class GDFlixNet : GDFlix() { override var mainUrl = "https://new18.gdflix.*" }

open class GDFlix : ExtractorApi() {
    override val name = "GDFlix"; override val mainUrl = "https://gdflix.*"; override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        var baseUrl = getBaseUrl(url); val latest = getLatestBaseUrl(baseUrl, "gdflix")
        var newUrl = url; if (baseUrl != latest) { newUrl = url.replace(baseUrl, latest); baseUrl = latest }
        val doc = runCatching { app.get(newUrl, referer = referer).document }.getOrNull() ?: return
        val fileName = doc.select("ul > li.list-group-item:contains(Name)").text().substringAfter("Name : ").orEmpty()
        val fileSize = doc.select("ul > li.list-group-item:contains(Size)").text().substringAfter("Size : ").orEmpty()
        val q = getIndexQuality(fileName)
        suspend fun emit(u: String, s: String = "") {
            callback.invoke(newExtractorLink("${name}${s}", "${name}${s} ${fileName}[${fileSize}]".trim(), u, ExtractorLinkType.VIDEO) { this.quality = q; this.referer = newUrl })
        }
        doc.select("div.text-center a").amap { anchor ->
            val t = anchor.select("a").text(); val l = anchor.attr("href")
            when {
                t.contains("FSL V2") -> emit(l, "[FSL V2]")
                t.contains("DIRECT DL") -> emit(l, "[Direct]")
                t.contains("DIRECT SERVER") -> emit(l, "[Direct]")
                t.contains("CLOUD DOWNLOAD [R2]") -> emit(l, "[Cloud]")
                t.contains("GD Index") -> {
                    listOf(1, 2).amap { cfType -> app.get(baseUrl + l + "?type=$cfType").document.select("a.btn-success").amap { emit(it.attr("href"), "[CF]") } }
                }
                t.contains("FAST CLOUD") -> {
                    val d = app.get(baseUrl + l).document.select("div.card-body a").attr("href"); if (d.isNotBlank()) emit(d, "[FAST CLOUD]")
                }
                l.contains("pixeldra") -> {
                    val b = getBaseUrl(l); val f = if (l.contains("download", true)) l else "$b/api/file/${l.substringAfterLast("/")}?download"; emit(f, "[Pixeldrain]")
                }
                t.contains("Instant DL") -> {
                    runCatching { val i = app.get(l, allowRedirects = false).headers["location"]?.substringAfter("url=").orEmpty(); if (i.isNotBlank()) emit(i, "[Instant]") }
                }
                t.contains("GoFile") -> runCatching { app.get(l).document.select(".row .row a").amap { g -> val gl = g.attr("href"); if (gl.contains("gofile")) loadExtractor(gl, "", subtitleCallback, callback) } }
            }
        }
    }
}

open class FastDlServer : ExtractorApi() {
    override val name: String = "fastdlserver"; override var mainUrl: String = "https://fastdlserver.*"; override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val loc = runCatching { app.get(url, allowRedirects = false).headers["location"] }.getOrNull()
        if (!loc.isNullOrBlank()) loadExtractor(loc, "", subtitleCallback, callback)
    }
}

class PlayDrive(private val label: String = "", private val sourceName: String = "") : ExtractorApi() {
    override val name = "PlayDrive"; override val mainUrl = "https://playdrive.vip"; override val requiresReferer = false
    private val SEAL_PREFIX = "pa!~"; private val SEAL_KEY = "pa4u-stream-seal-v1"

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val fh = mapOf("Cookie" to "playarchive_adev=1", "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36")
        val html = runCatching { app.get(url, headers = fh, referer = referer ?: mainUrl).text }.getOrNull() ?: return
        val json = extractPlayJson(html) ?: return
        var m3u8 = unseal(json.optJSONObject("item")?.optString("video_url") ?: "")
        if (!m3u8.startsWith("http")) json.optJSONArray("streams")?.let { arr ->
            for (i in 0 until arr.length()) { val s = arr.optJSONObject(i) ?: continue; val u = unseal(s.optString("m3u8_url")); if (u.startsWith("http")) { m3u8 = u; break } }
        }
        if (!m3u8.startsWith("http")) return
        var quality = 0
        json.optJSONObject("item")?.optJSONArray("qualities")?.let { arr ->
            for (i in 0 until arr.length()) { val lbl = arr.optJSONObject(i)?.optString("label") ?: continue; val n = Regex("""(\d{3,4})[pP]""").find(lbl)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0; if (n > quality) quality = n }
        }
        val finalLabel = listOfNotNull(sourceName.takeIf { it.isNotBlank() }, label.takeIf { it.isNotBlank() }).joinToString(" • ").ifBlank { "PlayDrive" }
        callback.invoke(newExtractorLink(name, finalLabel, m3u8, ExtractorLinkType.M3U8) {
            this.quality = quality; this.referer = "https://playdrive.vip/"; this.headers = mapOf("User-Agent" to fh["User-Agent"]!!, "Referer" to "https://playdrive.vip/")
        })
    }

    private fun extractPlayJson(html: String): JSONObject? {
        val idx = html.indexOf("window.__PA_PLAY__"); if (idx < 0) return null; val start = html.indexOf('{', idx); if (start < 0) return null
        var depth = 0; var inStr = false; var esc = false; var end = -1
        for (i in start until html.length) { val c = html[i]; if (inStr) { when { esc -> esc = false; c == '\\' -> esc = true; c == '"' -> inStr = false } } else { when (c) { '"' -> inStr = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) { end = i; break } } } } }
        if (end < 0) return null; return runCatching { JSONObject(html.substring(start, end + 1)) }.getOrNull()
    }

    private fun unseal(input: String): String {
        if (input.isBlank() || !input.startsWith(SEAL_PREFIX)) return input
        var b64 = input.removePrefix(SEAL_PREFIX).replace('-', '+').replace('_', '/'); val pad = b64.length % 4; if (pad > 0) b64 += "=".repeat(4 - pad)
        val dec = runCatching { java.util.Base64.getDecoder().decode(b64) }.getOrNull() ?: return ""
        val sb = StringBuilder(dec.size); for (i in dec.indices) { val b = dec[i].toInt() and 0xFF; val k = SEAL_KEY[i % SEAL_KEY.length].code; sb.append(((b xor k) and 0xFF).toChar()) }
        return sb.toString()
    }
}