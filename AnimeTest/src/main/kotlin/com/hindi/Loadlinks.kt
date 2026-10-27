package com.hindi

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageList
import java.net.URLEncoder
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.hindi.providers.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId

import com.hindi.providers.SourceProviders.invokeAllSources
import com.hindi.providers.SourceProviders.invokeAllAnimeSources
import com.hindi.providers.toSansSerifBold
import com.hindi.providers.toSansSerifItalic
import com.hindi.providers.toFlagEmoji
import com.hindi.providers.SourceProviders.invokeAnimes
import com.hindi.providers.AllLoadLinksData
import com.hindi.providers.convertImdbToAnimeId
import com.hindi.providers.convertTmdbToAnimeId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val res = parseJson<LoadLinksData>(data)

    // 1. Correct Year Mapping (year = Show Release Year | seasonYear = Episode Release Year)
    val year = getYear(res) ?: res.airedYear
    val seasonYear = getSeasonYear(res) ?: res.airedYear ?: year

    // 2. Duplicate Links Filter
    val seenUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val safeCallback: (ExtractorLink) -> Unit = { link ->
        val normalizedUrl = link.url.trim()
        if (normalizedUrl.isNotEmpty() && seenUrls.add(normalizedUrl)) {
            callback(link)
        }
    }

    // 3. Recover IMDB ID (Cinemeta/Stremio fallback for VegaMovies, Hdhub4u, etc.)
    val effectiveImdbId = res.imdb_id?.takeIf { it.startsWith("tt") }
        ?: res.id.takeIf { it.startsWith("tt") }

    // 4. Resolve Missing Anime IDs (AniList & MAL)
    var finalAniId = res.anilistId
    var finalMalId = res.malId
    var animeSource = "imdb"

    if (res.isAnime && finalAniId == null && finalMalId == null) {
        val imdbResult = convertImdbToAnimeId(
            res.title,
            year,
            res.firstAired,
            if (res.tvtype == "movie") TvType.AnimeMovie else TvType.Anime
        )
        finalAniId = imdbResult.id
        finalMalId = imdbResult.idMal

        if (finalAniId == null && finalMalId == null) {
            val tmdbResult = convertTmdbToAnimeId(
                res.title,
                year?.toString(),
                res.airedDate ?: res.firstAired,
                if (res.tvtype == "movie") TvType.AnimeMovie else TvType.Anime
            )
            finalAniId = tmdbResult.id
            finalMalId = tmdbResult.idMal
            animeSource = "tmdb"
        }
    }

    // 5. Fallback-Safe Season, Episode & Title Mapping
    val effectiveSeason = if (res.tvtype == "movie") null else (res.season ?: res.imdbSeason)
    val effectiveEpisode = if (res.tvtype == "movie") null else (res.episode ?: res.imdbEpisode)
    val effectiveImdbSeason = if (res.tvtype == "movie") null else (res.imdbSeason ?: res.season)
    val effectiveImdbEpisode = if (res.tvtype == "movie") null else (res.imdbEpisode ?: res.episode)
    val effectiveTitle = res.title ?: res.orgTitle
    val effectiveImdbTitle = res.title ?: res.orgTitle
 // ⚡ 1. FAST START CACHE CHECK ⚡
    val cacheKey = FastStartCache.key(res.tmdbId, effectiveImdbId, effectiveSeason, effectiveEpisode)
    val cachedLinks = FastStartCache.get(cacheKey)

    if (!cachedLinks.isNullOrEmpty()) {
        android.util.Log.d("StreamHub", "🔥 FastStartCache HIT! Loaded ${cachedLinks.size} streams.")
        cachedLinks.forEach { callback(it) }
        // Let it continue so heavy scrapers can still find more in background
    }

    // ⚡ 2. SMART CALLBACK (Saves new links to cache instantly) ⚡
    val smartCallback: (ExtractorLink) -> Unit = { link ->
        val normalizedUrl = link.url.trim()
        if (normalizedUrl.isNotEmpty() && seenUrls.add(normalizedUrl)) {
            FastStartCache.put(cacheKey, link) // Save to cache
            callback(link)
        }
    }

    return when {
        res.isKitsu -> {
            runKitsuInvokers(
                res = res,
                effectiveImdbId = effectiveImdbId,
                finalAniId = finalAniId,
                finalMalId = finalMalId,
                year = year,
                seasonYear = seasonYear,
                subtitleCallback = subtitleCallback,
                callback = safeCallback
            )
            true
        }
        else -> {
            val payload = AllLoadLinksData(
                title = effectiveTitle,
                imdbId = effectiveImdbId,
                tmdbId = res.tmdbId,
tvtype = res.tvtype,
                anilistId = finalAniId,
                malId = finalMalId,
                kitsuId = res.kitsuId,
                year = year,
                airedYear = seasonYear,
                season = effectiveSeason,
                episode = effectiveEpisode,
                isAnime = res.isAnime,
                isBollywood = res.isBollywood,
                isAsian = res.isAsian,
                isCartoon = res.isCartoon,
                originalTitle = res.orgTitle ?: effectiveTitle,
                imdbTitle = effectiveImdbTitle,
                imdbSeason = effectiveImdbSeason,
                imdbEpisode = effectiveImdbEpisode,
                imdbYear = year
            )

              // ⚡ 3. TWO-TIER RACING ENGINE ⚡
            kotlinx.coroutines.coroutineScope {
                // Tier 1: Race Fast APIs (Milliseconds)
                launch { ApiRacer.raceFastApis(payload, subtitleCallback, smartCallback) }
                
                // Tier 2: Heavy HTML Scrapers (Runs slowly in background)
                launch {
                    if (res.isAnime) {
                        runAllAsync(
                            { invokeAllAnimeSources(payload, subtitleCallback, smartCallback) },
                            { invokeAnimes(finalMalId, finalAniId, effectiveEpisode, seasonYear, animeSource, subtitleCallback, smartCallback) }
                        )
                    } else {
                        invokeAllSources(payload, subtitleCallback, smartCallback)
                    }
                }
            }
            true
        }
    }
}

data class LoadLinksData(
    val title: String? = null,
    val id: String,
    val tmdbId: Int?,
    val tvtype: String,
    val year: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val firstAired: String? = null,
    val isAnime: Boolean = false,
    val isBollywood: Boolean = false,
    val isAsian: Boolean = false,
    val isCartoon: Boolean = false,
    val imdb_id : String? = null,
    val imdbSeason : Int? = null,
    val imdbEpisode : Int? = null,
    val isKitsu : Boolean = false,
    val anilistId : Int? = null,
    val malId : Int? = null,
    val kitsuId : String? = null,

    val orgTitle: String? = null,
    val airedYear: Int? = null,
    val airedDate: String? = null,

    val animeId: String? = null,
    val tvdbId: Int? = null,

    val epid: Int? = null,
    val lastSeason: Int? = null,
    val epsTitle: String? = null,
    val jpTitle: String? = null,

    val alttitle: String? = null,
    val nametitle: String? = null,
)

data class PassData(
    val id: String,
    val type: String,
)

data class Meta(
    val id: String?,
    val imdb_id: String?,
    val awards: String?,
    val type: String?,
    val aliases: ArrayList<String>?,
    val poster: String?,
    val logo: String?,
    val background: String?,
    val moviedb_id: Int?,
    val name: String?,
    val description: String?,
    val genre: List<String>?,
    val genres: List<String>?,
    val releaseInfo: String?,
    val status: String?,
    val runtime: String?,
    val cast: List<String>?,
    val app_extras: AppExtras? = null,
    val language: String?,
    val country: String?,
    val imdbRating: String?,
    val year: String?,
    val videos: List<EpisodeDetails>?,
)

data class AppExtras (
    val cast: List<Cast> = emptyList()
)

data class Cast (
    val name      : String? = null,
    val character : String? = null,
    val photo     : String? = null
)

data class SearchResult(
    val metas: List<Media>
)

data class Media(
    val id: String,
    val type: String,
    val name: String?,
    val poster: String?,
    val description: String?,
    val imdbRating: String?,
    val aliases: ArrayList<String>?,
)

data class EpisodeDetails(
    val id: String?,
    val name: String?,
    val title: String?,
    val season: Int,
    val episode: Int,
    val rating: String?,
    val released: String?,
    val firstAired: String?,
    val overview: String?,
    val thumbnail: String?,
    val moviedb_id: Int?,
    val imdb_id: String?,
    val imdbSeason: Int?,
    val imdbEpisode: Int?,
)

data class ResponseData(
    val meta: Meta,
)

data class Home(
    val metas: List<Media>,
    val hasMore: Boolean = true,
)

data class ExtenalIds(
    val anilist: Int? = null,
    val anidb: Int? = null,
    val myanimelist: Int? = null,
    val kitsu: Int? = null,
    val anisearch: Int? = null,
    val livechart: Int? = null,
    val themoviedb: Int? = null,
    val imdb: String? = null,
)

suspend fun getExternalIds(id: String, type: String) : ExtenalIds? {
    val url = "${ApiConstants.HAGLUND_BASE}/ids?source=$type&id=$id"
    val json = app.get(url).text
    return tryParseJson<ExtenalIds>(json) ?: return null
}

private fun getYear(res: LoadLinksData): Int? {
    return if (res.tvtype == "movie") res.year?.toIntOrNull()
    else res.year?.substringBefore("-")?.toIntOrNull()
        ?: res.year?.substringBefore("–")?.toIntOrNull()
        ?: res.year?.toIntOrNull()
}

private fun getSeasonYear(res: LoadLinksData): Int? {
    return if (res.tvtype == "movie") getYear(res)
    else res.firstAired?.substringBefore("-")?.toIntOrNull()
        ?: res.firstAired?.substringBefore("–")?.toIntOrNull()
        ?: res.airedYear
        ?: getYear(res)
}

private suspend fun runKitsuInvokers(
    res: LoadLinksData,
    effectiveImdbId: String?,
    finalAniId: Int?,
    finalMalId: Int?,
    year: Int?,
    seasonYear: Int?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    var imdbTitle: String? = null
    var imdbYear: Int? = null
    var tmdbId: Int? = res.tmdbId

    var resolvedAniId = finalAniId
    var resolvedMalId = finalMalId
    var resolvedImdbId = effectiveImdbId

    // Haglund API fallback for Kitsu Anime
    if (!res.kitsuId.isNullOrBlank() && (resolvedAniId == null || resolvedImdbId == null)) {
        runCatching {
            val ext = getExternalIds(res.kitsuId, "kitsu")
            if (resolvedAniId == null) resolvedAniId = ext?.anilist
            if (resolvedMalId == null) resolvedMalId = ext?.myanimelist
            if (resolvedImdbId == null) resolvedImdbId = ext?.imdb
            if (tmdbId == null) tmdbId = ext?.themoviedb
        }
    }

    if (!resolvedImdbId.isNullOrBlank() && resolvedImdbId.startsWith("tt")) {
        try {
            val json = app.get(
                "${ApiConstants.CINEMETA_BASE}/meta/${res.tvtype}/$resolvedImdbId.json"
            ).text
            val movieData = tryParseJson<ResponseData>(json)

            movieData?.meta?.let { meta ->
                imdbTitle = meta.name
                imdbYear = meta.year?.substringBefore("-")?.toIntOrNull()
                    ?: meta.year?.substringBefore("–")?.toIntOrNull()
                    ?: meta.year?.toIntOrNull()
                if (tmdbId == null) tmdbId = meta.moviedb_id
            }
        } catch (e: Exception) {
            println("Cinemeta API failed: ${e.localizedMessage}")
        }
    }

    val effectiveSeason = if (res.tvtype == "movie") null else (res.imdbSeason ?: res.season)
    val effectiveEpisode = if (res.tvtype == "movie") null else (res.imdbEpisode ?: res.episode)

    invokeAllAnimeSources(
        AllLoadLinksData(
            title = res.title,
            imdbId = resolvedImdbId,
            tmdbId = tmdbId,
            anilistId = resolvedAniId,
            malId = resolvedMalId,
            kitsuId = res.kitsuId,
            year = year,
            airedYear = seasonYear,
            season = res.season ?: effectiveSeason,
            episode = res.episode ?: effectiveEpisode,
            isAnime = true,
            isBollywood = res.isBollywood,
            isAsian = res.isAsian,
            isCartoon = res.isCartoon,
            originalTitle = res.orgTitle ?: res.title,
            imdbTitle = imdbTitle ?: res.title,
            imdbSeason = effectiveSeason,
            imdbEpisode = effectiveEpisode,
            imdbYear = imdbYear ?: year
        ),
        subtitleCallback,
        callback
    )
}