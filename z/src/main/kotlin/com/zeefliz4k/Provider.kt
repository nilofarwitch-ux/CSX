package com.zeefliz4k

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class ZeeFliz4K : MainAPI() {
    override var mainUrl = "https://zeefliz4k.cfd"
    override var name = "ZeeFliz4K"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    companion object {
        private const val CINEMETA_URL = "https://v3-cinemeta.strem.io"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Releases",
        "$mainUrl/category/movies/" to "Latest Movies",
        "$mainUrl/category/hindi/" to "Hindi Movies",
        "$mainUrl/category/english/" to "English Movies",
        "$mainUrl/category/series/" to "Web Series",
        "$mainUrl/category/action-series/" to "Action Series",
        "$mainUrl/category/korean/" to "Korean Series",
        "$mainUrl/category/drama-series/" to "Drama Series",
        "$mainUrl/category/anime/" to "Anime",
        "$mainUrl/category/imax/" to "IMAX"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val document = app.get(url).document
        val items = document.select("div.card-grid > a.movie-card")
            .mapNotNull { it.toSearchResponse() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/?s=$encodedQuery").document

        return document.select("div.card-grid > a.movie-card")
            .mapNotNull { it.toSearchResponse() }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val title = this.selectFirst("h3.movie-card-title")?.text()?.trim() ?: return null

        val rawPoster = this.selectFirst("div.movie-card-image img")?.attr("src")
        val posterUrl = fixUrlNull(
            rawPoster?.replace(Regex("-\\d+x\\d+(?=\\.\\w+$)"), "")
        )

        val metaText = this.selectFirst("p.movie-card-meta")?.text()?.trim().orEmpty()
        val qualityText = this.select("div.zf-quality-tags span.zf-tag")
            .joinToString(" ") { it.text().trim() }

        val isSeries = metaText.contains(Regex("S\\d+", RegexOption.IGNORE_CASE)) ||
                href.contains("-series-", ignoreCase = true)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addQuality(qualityText)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(qualityText)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val rawTitle = document.selectFirst("h1.page-title, header.zfc-hero h1")?.text()?.trim()
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("img.zfc-poster-image, img.wp-post-image")?.attr("src")
                ?.replace(Regex("-\\d+x\\d+(?=\\.\\w+$)"), "")
        )

        val metaMap = document.select("#zfc-raw .metadata-list .metadata-item").associate { item ->
            val key = item.selectFirst(".metadata-label")?.text()?.trim()?.removeSuffix(":")?.lowercase().orEmpty()
            val value = item.selectFirst(".metadata-value")?.text()?.trim().orEmpty()
            key to value
        }

        val extractedYear = Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("\\b((?:19|20)\\d{2})\\b")
                .find(metaMap["release"].orEmpty() + " " + metaMap["last air"].orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull()

        val sitePlot = document.selectFirst("#zfc-raw .content-section > p.mt-4")?.text()?.trim()
        val siteCast = metaMap["stars"]?.split(",")
            ?.map { it.substringBefore("(").trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        val ignoredBadges = setOf("1080p", "2160p", "720p", "480p", "10bit", "dv hdr", "sdr", "movies", "series")
        val siteTags = document.select("#zfc-raw span.badge a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.lowercase() !in ignoredBadges }

        val trailerUrl = document.selectFirst("#trailer-btn")?.attr("data-trailer-url")
            ?.takeIf { it.isNotBlank() }

        val isSeries = url.contains("-series-", ignoreCase = true) ||
                document.selectFirst("#episodes, #complete-pack, .episode-number") != null

        val cinemetaType = if (isSeries) "series" else "movie"
        val cinemeta = fetchCinemetaMeta(rawTitle, extractedYear, cinemetaType)

        val finalPlot = cinemeta?.description?.takeIf { it.isNotBlank() }
            ?.let { Parser.unescapeEntities(it, false) }
            ?: sitePlot
        val finalTags = (cinemeta?.genres.orEmpty() + siteTags).distinct()
        val finalYear = extractedYear ?: cinemeta?.releaseInfo?.take(4)?.toIntOrNull()
        val finalCast = (cinemeta?.cast?.takeIf { it.isNotEmpty() } ?: siteCast)
            .map { Parser.unescapeEntities(it, false) }

        if (isSeries) {
            val episodes = parseSeriesEpisodes(document, cinemeta?.videos.orEmpty())
            return newTvSeriesLoadResponse(rawTitle, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = cinemeta?.background ?: poster
                this.year = finalYear
                this.plot = finalPlot
                this.tags = finalTags
                this.score = Score.from10(cinemeta?.imdbRating)
                addActors(finalCast)
                addDuration(cinemeta?.runtime)
                addTrailer(trailerUrl)
            }
        } else {
            val downloadLinks = parseDownloadItems(document.select("#zfc-raw div.download-item"))
            return newMovieLoadResponse(rawTitle, url, TvType.Movie, downloadLinks.toJson()) {
                this.posterUrl = poster
                this.backgroundPosterUrl = cinemeta?.background ?: poster
                this.year = finalYear
                this.plot = finalPlot
                this.tags = finalTags
                this.score = Score.from10(cinemeta?.imdbRating)
                addActors(finalCast)
                addDuration(cinemeta?.runtime)
                addTrailer(trailerUrl)
            }
        }
    }

    private fun parseDownloadItems(items: List<Element>): List<ZeeDownloadData> {
        val results = mutableListOf<ZeeDownloadData>()

        for (item in items) {
            val titleContainer = item.selectFirst(".download-header .flex-1")
            val rawHeaderTitle = titleContainer?.ownText()?.trim()?.takeIf { it.isNotBlank() }
                ?: titleContainer?.selectFirst("p")?.ownText()?.trim().orEmpty()

            val qualityLabel = rawHeaderTitle
                .substringAfter("(", "")
                .substringBeforeLast(")", "")
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { item.selectFirst(".file-title")?.text()?.trim().orEmpty() }

            val headerBadges = item.select(".download-header code span.badge").map { it.text().trim() }
            val size = headerBadges.firstOrNull { it.contains(Regex("(GB|MB)", RegexOption.IGNORE_CASE)) }.orEmpty()
            val langs = headerBadges.firstOrNull {
                !it.contains(Regex("(GB|MB|WEB-DL|BluRay|REMUX|HDRip)", RegexOption.IGNORE_CASE))
            }.orEmpty()

            item.select("a.btn[href]").forEach { btn ->
                val linkUrl = fixUrlNull(btn.attr("href")) ?: return@forEach
                val hostLabel = btn.text().replace("Download", "", ignoreCase = true).trim()

                results.add(
                    ZeeDownloadData(
                        url = linkUrl,
                        quality = qualityLabel,
                        size = size,
                        langs = langs,
                        host = hostLabel
                    )
                )
            }
        }
        return results
    }

    private fun parseSeriesEpisodes(
        document: Element,
        cinemetaVideos: List<CinemetaVideo>
    ): List<Episode> {
        val groupedLinks = linkedMapOf<Pair<Int, Int>, MutableList<ZeeDownloadData>>()
        val parsedEpTitles = mutableMapOf<Pair<Int, Int>, String>()

        val qualityBlocks = document.select("#episodes div.season-item.episode-item")
        for (block in qualityBlocks) {
            val defaultSeason = block.selectFirst(".episode-header .episode-number")?.text()
                ?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 1

            val qualityTitle = block.selectFirst(".episode-header h3.episode-title")?.text()
                ?.replace(Regex("^S\\d+\\s*", RegexOption.IGNORE_CASE), "")
                ?.trim()
                .orEmpty()

            val langs = block.select(".episode-header .episode-meta span.badge")
                .map { it.text().trim() }
                .firstOrNull { !it.startsWith("Episodes", ignoreCase = true) }
                .orEmpty()

            val epItems = block.select(".episode-downloads .episode-download-item")
            for (epItem in epItems) {
                val fileName = epItem.selectFirst(".episode-file-title")?.text()?.trim().orEmpty()
                val badgeEpText = epItem.selectFirst(".badge-psa")?.text()?.trim().orEmpty()
                val size = epItem.selectFirst(".badge-size")?.text()?.trim().orEmpty()

                val seMatch = Regex("S(\\d+)E(\\d+)", RegexOption.IGNORE_CASE).find(fileName)
                val seasonNum = seMatch?.groupValues?.get(1)?.toIntOrNull() ?: defaultSeason
                val episodeNum = seMatch?.groupValues?.get(2)?.toIntOrNull()
                    ?: Regex("(\\d+)").find(badgeEpText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: continue

                val key = seasonNum to episodeNum

                if (key !in parsedEpTitles) {
                    val extractedName = Regex(
                        "S\\d+E\\d+[.\\s-]+(.+?)[.\\s-]+(?:REPACK|PROPER|2160p|1080p|720p|480p|UHD|WEB-DL|BluRay)",
                        RegexOption.IGNORE_CASE
                    ).find(fileName)?.groupValues?.get(1)
                        ?.replace(".", " ")
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }

                    if (extractedName != null) {
                        parsedEpTitles[key] = extractedName
                    }
                }

                val bucket = groupedLinks.getOrPut(key) { mutableListOf() }
                epItem.select(".episode-links a.btn[href]").forEach { btn ->
                    val linkUrl = fixUrlNull(btn.attr("href")) ?: return@forEach
                    val hostLabel = btn.text().replace("Download", "", ignoreCase = true).trim()

                    bucket.add(
                        ZeeDownloadData(
                            url = linkUrl,
                            quality = qualityTitle,
                            size = size,
                            langs = langs,
                            host = hostLabel
                        )
                    )
                }
            }
        }

        val seasonsWithIndividualEps = groupedLinks.keys.map { it.first }.toSet()
        val packItems = document.select("#complete-pack div.download-item, #zfc-raw div.download-item")
        val packSeasons = linkedMapOf<Int, MutableList<ZeeDownloadData>>()

        for (item in packItems) {
            val seasonText = item.selectFirst(".download-header .episode-number")?.text()?.trim().orEmpty()
            val seasonNum = Regex("(\\d+)").find(seasonText)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (seasonNum in seasonsWithIndividualEps) continue

            val links = parseDownloadItems(listOf(item))
            if (links.isNotEmpty()) {
                packSeasons.getOrPut(seasonNum) { mutableListOf() }.addAll(links)
            }
        }

        val cinemetaVideoMap = cinemetaVideos.associateBy { (it.season ?: 1) to (it.episode ?: 0) }
        val finalEpisodes = mutableListOf<Episode>()

        groupedLinks.entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .forEach { (key, links) ->
                val (seasonNum, episodeNum) = key
                val metaEp = cinemetaVideoMap[key]
                val epName = metaEp?.name?.takeIf { it.isNotBlank() }
                    ?.let { Parser.unescapeEntities(it, false) }
                    ?: parsedEpTitles[key]
                    ?: "Episode $episodeNum"

                finalEpisodes.add(
                    newEpisode(links.toJson()) {
                        this.season = seasonNum
                        this.episode = episodeNum
                        this.name = epName
                        this.posterUrl = metaEp?.thumbnail
                        this.description = metaEp?.overview?.let { Parser.unescapeEntities(it, false) }
                        addDate(metaEp?.released)
                    }
                )
            }

        packSeasons.entries
            .sortedBy { it.key }
            .forEach { (seasonNum, links) ->
                finalEpisodes.add(
                    newEpisode(links.toJson()) {
                        this.season = seasonNum
                        this.episode = 1
                        this.name = "Season $seasonNum (Complete Pack)"
                    }
                )
            }

        return finalEpisodes
    }

        override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = runCatching { parseJson<List<ZeeDownloadData>>(data) }.getOrNull() ?: return false

        val seenResolvedUrls = ConcurrentHashMap.newKeySet<String>()
        val seenStreamUrls = ConcurrentHashMap.newKeySet<String>()

        links.amap { entry ->
            runCatching {
                withTimeoutOrNull(15_000L) {
                    val resolvedUrl = resolveZeeLink(entry.url)
                    if (resolvedUrl.isBlank()) return@withTimeoutOrNull

                    val extractorUrl = if (resolvedUrl.contains("zee4k.lol/drive/", ignoreCase = true)) {
                        resolvedUrl.replace("zee4k.lol", "hubcloud.dad", ignoreCase = true)
                    } else {
                        resolvedUrl
                    }

                    if (!seenResolvedUrls.add(extractorUrl)) return@withTimeoutOrNull

                    val inferredQuality = getIndexQuality(entry.quality)
                    val collected = mutableListOf<ExtractorLink>()

                    loadExtractor(extractorUrl, entry.url, subtitleCallback) { link ->
                        synchronized(collected) {
                            collected.add(link)
                        }
                    }

                    for (link in collected) {
                        if (seenStreamUrls.add(link.url)) {
                            val serverTag = link.source.ifBlank { entry.host }
                            val cleanDisplayName = listOfNotNull(
                                entry.quality.takeIf { it.isNotBlank() }?.let { "[$it]" },
                                entry.size.takeIf { it.isNotBlank() }?.let { "[$it]" },
                                entry.langs.takeIf { it.isNotBlank() }?.let { "[$it]" },
                                serverTag.takeIf { it.isNotBlank() }
                            ).joinToString(" ")

                            val finalQuality = if (
                                link.quality == Qualities.Unknown.value &&
                                inferredQuality != Qualities.Unknown.value
                            ) {
                                inferredQuality
                            } else {
                                link.quality
                            }

                            callback.invoke(
                                newExtractorLink(
                                    serverTag,
                                    cleanDisplayName.ifBlank { link.name },
                                    link.url,
                                    link.type
                                ) {
                                    this.quality = finalQuality
                                    this.referer = link.referer
                                    this.headers = link.headers
                                    this.extractorData = link.extractorData
                                }
                            )
                        }
                    }
                }
            }
        }
        return true
    }

    private suspend fun resolveZeeLink(rawUrl: String): String {
        if (!rawUrl.contains("zee4k.lol", ignoreCase = true)) return rawUrl
        if (rawUrl.contains("/drive/", ignoreCase = true)) {
            return rawUrl.replace("zee4k.lol", "hubcloud.dad", ignoreCase = true)
        }

        return runCatching {
            val response = app.get(
                url = rawUrl,
                referer = "$mainUrl/",
                allowRedirects = true
            )
            val landedUrl = response.url
            if (!landedUrl.contains("zee4k.lol", ignoreCase = true)) {
                return@runCatching landedUrl
            }
            if (landedUrl.contains("/drive/", ignoreCase = true)) {
                return@runCatching landedUrl.replace("zee4k.lol", "hubcloud.dad", ignoreCase = true)
            }

            val html = response.text
            Regex("""content=["']\d+;\s*url=([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)?.let { return@runCatching it }

            Regex("""(?:window\.)?location(?:\.href|\.replace)?\s*(?:=|\()\s*["'](https?://[^"']+)["']""")
                .find(html)?.groupValues?.get(1)?.let { return@runCatching it }

            response.document.selectFirst("a[href*='hubcloud'], a[href*='hubdrive'], a[href*='/drive/'], a.btn[href^='http']")
                ?.attr("href") ?: landedUrl
        }.getOrDefault(rawUrl)
    }

    private suspend fun fetchCinemetaMeta(
        rawTitle: String,
        year: Int?,
        type: String
    ): CinemetaMeta? {
        val baseTitle = rawTitle
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("\\b(Season\\s*\\d+|S\\d+).*$", RegexOption.IGNORE_CASE), "")
            .trim()

        val candidates = listOfNotNull(
            baseTitle,
            baseTitle.substringBefore(":").trim().takeIf { it != baseTitle && it.length > 3 }
        ).distinct()

        for (queryTitle in candidates) {
            val encoded = URLEncoder.encode(queryTitle, "UTF-8")
            val searchRes = runCatching {
                app.get("$CINEMETA_URL/catalog/$type/top/search=$encoded.json")
                    .parsedSafe<CinemetaSearchResponse>()
            }.getOrNull()

            val matched = searchRes?.metas?.firstOrNull { meta ->
                year == null || meta.releaseInfo?.startsWith(year.toString()) == true
            } ?: searchRes?.metas?.firstOrNull()

            val imdbId = matched?.id ?: continue

            val fullMeta = runCatching {
                app.get("$CINEMETA_URL/meta/$type/$imdbId.json")
                    .parsedSafe<CinemetaMetaResponse>()?.meta
            }.getOrNull()

            if (fullMeta != null) return fullMeta
        }
        return null
    }

    data class ZeeDownloadData(
        @JsonProperty("url") val url: String,
        @JsonProperty("quality") val quality: String,
        @JsonProperty("size") val size: String,
        @JsonProperty("langs") val langs: String,
        @JsonProperty("host") val host: String
    )

    data class CinemetaSearchResponse(
        @JsonProperty("metas") val metas: List<CinemetaMeta>? = null
    )

    data class CinemetaMetaResponse(
        @JsonProperty("meta") val meta: CinemetaMeta? = null
    )

    data class CinemetaMeta(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("background") val background: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("imdbRating") val imdbRating: String? = null,
        @JsonProperty("releaseInfo") val releaseInfo: String? = null,
        @JsonProperty("runtime") val runtime: String? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("cast") val cast: List<String>? = null,
        @JsonProperty("videos") val videos: List<CinemetaVideo>? = null
    )

    data class CinemetaVideo(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("released") val released: String? = null
    )
}
