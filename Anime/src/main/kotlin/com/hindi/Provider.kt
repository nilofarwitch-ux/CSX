@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNUSED_PARAMETER", "UNCHECKED_CAST")
package com.hindi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.hindi.providers.toFlagEmoji
import com.hindi.providers.toSansSerifBold
import com.hindi.providers.toSansSerifItalic
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder

class Cwunchyroll : MainAPI() {

    override var name = "Cwunchyroll"
    override var mainUrl = ApiConstants.ANILIST_API
    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie, TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "anilist_trending" to "🔥 Trending Now",
        "anilist_season"   to "📺 Popular This Season",
        "anilist_top"      to "🏆 Top Rated",
        "anilist_upcoming" to "🆕 Upcoming Anime",
        "anilist_airing"   to "✨ Airing Now",
        "anilist_movies"   to "🎬 Popular Movies",
        "anilist_ova"      to "🎭 OVAs",
        "anilist_ona"      to "🌟 ONAs",
        "anilist_action"   to "⚔️ Action",
        "anilist_comedy"   to "😂 Comedy",
        "anilist_romance"  to "💕 Romance",
        "anilist_fantasy"  to "🐉 Fantasy",
        "anilist_scifi"    to "🌌 Sci-Fi",
        "anilist_horror"   to "👻 Horror",
        "anilist_school"   to "🏫 School Life",
        "anilist_psych"    to "🧠 Psychological",
        "anilist_isekai"   to "🎯 Isekai",
        "anilist_shounen"  to "⚡ Shounen",
        "anilist_hidden"   to "💎 Hidden Gems",
        "jikan_favorites"  to "⭐ Most Favorited (MAL)",
        "jikan_members"    to "👥 Most Members (MAL)",
        "jikan_airing"     to "📡 Top Airing (MAL)",
        "kitsu_trending"   to "👥 Kitsu Community Trending",
        "kitsu_top"        to "🎞️ Kitsu Top Rated",
        "kitsu_airing"     to "📺 Kitsu Airing Now",
        "tmdb_grossing"    to "💰 Highest Grossing Anime Films",
        "tmdb_hindi_dub"   to "🇮🇳 Hindi Dub Anime",
        "tmdb_recent"      to "🎥 Recent Theatrical Releases"
    )

    // ═══════════════════ HOMEPAGE ═══════════════════
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        HomepageDedupTracker.startIfNewSession()
        val raw = fetchCategory(page, request.data)
        val filtered = raw.filter {
            HomepageDedupTracker.tryClaim(
                title = it.title, year = it.year, format = it.format, sourceId = it.sourceId
            )
        }
        return newHomePageResponse(request.name, filtered.map { it.response })
    }

    // ═══════════════════ SEARCH ═══════════════════
    override suspend fun search(query: String): List<SearchResponse> {
        val gql = """
            query (${'$'}search: String) {
              Page(page: 1, perPage: 50) {
                media(search: ${'$'}search, type: ANIME) {
                  id format seasonYear averageScore
                  title { romaji english native }
                  coverImage { extraLarge }
                }
              }
            }
        """.trimIndent()
        val json = runCatching {
            app.post(ApiConstants.ANILIST_API,
                json = mapOf("query" to gql, "variables" to mapOf("search" to query))).text
        }.getOrNull() ?: return emptyList()

        val media = tryParseJson<Map<String, Any>>(json)
            ?.let { it["data"] as? Map<*, *> }
            ?.let { it["Page"] as? Map<*, *> }
            ?.let { it["media"] as? List<Map<String, Any>> }
            ?: return emptyList()

        return media.mapNotNull { item ->
            val id = (item["id"] as? Number)?.toInt() ?: return@mapNotNull null
            val format = item["format"]?.toString() ?: "TV"
            val t = item["title"] as? Map<*, *>
            val title = t?.get("english")?.toString() ?: t?.get("romaji")?.toString()
                ?: t?.get("native")?.toString() ?: return@mapNotNull null
            val cover = ((item["coverImage"] as? Map<*, *>)?.get("extraLarge"))?.toString()
            val avg = (item["averageScore"] as? Number)?.toDouble()
            newAnimeSearchResponse(
                title,
                AniData(id = id, source = "anilist", format = format).toJson(),
                if (format == "MOVIE") TvType.AnimeMovie else TvType.Anime
            ) {
                posterUrl = cover
                this.year = (item["seasonYear"] as? Number)?.toInt()
                avg?.let { score = (it * 100).toInt() }
            }
        }
    }

    // ═══════════════════ LOAD ═══════════════════
    override suspend fun load(url: String): LoadResponse? {
        val aniData = parseJson<AniData>(url) ?: return null

        val aniListId = when (aniData.source) {
            "anilist" -> aniData.id
            "jikan"   -> resolveAniListIdFromMal(aniData.id)
            "kitsu"   -> resolveAniListIdFromKitsu(aniData.id)
            "tmdb"    -> resolveAniListIdFromTmdb(aniData.id)
            else -> null
        } ?: return null

        val ani = getAniListMedia(aniListId) ?: return null
        val title = ani.title?.english ?: ani.title?.romaji ?: ani.title?.native ?: return null

        suspend fun tmdbSearch(q: String?): TmdbMultiSearchResponse? {
            if (q.isNullOrBlank()) return null
            val u = "${ApiConstants.TMDB_BASE}/search/multi?api_key=${ApiConstants.TMDB_KEY}&query=${URLEncoder.encode(q, "UTF-8")}"
            return runCatching { app.get(u).parsed<TmdbMultiSearchResponse>() }.getOrNull()
        }

        val searches = coroutineScope {
            listOfNotNull(ani.title?.english, ani.title?.romaji, ani.title?.native)
                .distinct().map { async { tmdbSearch(it) } }.awaitAll()
        }
        val bestSearch = searches.firstOrNull { !it?.results.isNullOrEmpty() }
        val tmdbResult = selectBestTmdbResult(bestSearch?.results ?: emptyList(), ani, title, aniData.format)

        val mediaType = tmdbResult?.media_type ?: if (aniData.format == "MOVIE") "movie" else "tv"
        val tmdbId = tmdbResult?.id

        val imdbId = tmdbId?.let {
            runCatching {
                app.get("${ApiConstants.TMDB_BASE}/$mediaType/$it?api_key=${ApiConstants.TMDB_KEY}&append_to_response=external_ids")
                    .parsed<TmdbDetails>()?.external_ids?.imdbId
            }.getOrNull()
        }

        val aniZip = runCatching { fetchAniZip(anilistId = ani.id) }.getOrNull()

        val metadata = AnimeMetadataAggregator.aggregate(
            imdbId = imdbId, tmdbId = tmdbId, mediaType = mediaType,
            title = title, aniListId = ani.id, preFetchedAniList = ani
        )

        val linkData = LoadLinksData(
            title = metadata.title ?: title,
            id = metadata.imdbId ?: tmdbId?.toString() ?: ani.id.toString(),
            tmdbId = tmdbId, tvtype = mediaType,
            year = metadata.year?.toString(),
            isAnime = true, isCartoon = true,
            imdb_id = metadata.imdbId,
            anilistId = metadata.anilistId,
            malId = metadata.malId,
            kitsuId = metadata.kitsuId?.toString(),
            orgTitle = metadata.originalTitle,
            airedYear = metadata.year
        )

        return if (mediaType == "movie") {
            newMovieLoadResponse(metadata.title ?: title, url, TvType.AnimeMovie, linkData.toJson()) {
                posterUrl = metadata.poster
                backgroundPosterUrl = metadata.backdrop
                logoUrl = metadata.logo
                plot = buildPlot(metadata)
                tags = metadata.genres
                year = metadata.year
                contentRating = metadata.certification
                duration = metadata.runtime
                actors = metadata.cast.map { ActorData(Actor(it.name, it.image), roleString = it.role) }
                val sc = metadata.imdbRating ?: metadata.tmdbRating
                if (sc != null) score = (sc * 1000).toInt()
                addImdbId(metadata.imdbId)
                addAniListId(metadata.anilistId)
                addMalId(metadata.malId)
                metadata.trailer?.let { addTrailer(it) }
            }
        } else {
            buildSeriesResponse(tmdbId, metadata, ani, url, aniZip)
        }
    }

    private fun buildPlot(meta: AnimeMetadataAggregator.AggregatedMetadata): String = buildString {
        meta.countries.firstOrNull()?.let {
            val flag = it.isoCode?.toFlagEmoji().orEmpty()
            append("${"Origin".toSansSerifBold()}: $flag ${it.name.toSansSerifItalic()}\n\n")
        }
        meta.awards?.let { append("${"🏆 Awards".toSansSerifBold()}: ${it.toSansSerifItalic()}\n\n") }
        if (meta.streamingProviders.isNotEmpty()) {
            append("${"📺 Streaming".toSansSerifBold()}: ${meta.streamingProviders.joinToString(", ").toSansSerifItalic()}\n\n")
        }
        append("${"Description".toSansSerifBold()}: ${meta.description?.toSansSerifItalic() ?: ""}")
    }

    private fun selectBestTmdbResult(
        results: List<TmdbSearchResult>, ani: AniListMedia, title: String, format: String
    ): TmdbSearchResult? = results.maxByOrNull { item ->
        var s = 0
        val tt = (item.name ?: item.title ?: item.originalName ?: item.originalTitle ?: "").trim()
        if (tt.equals(title, true)) s += 100
        if (item.originalLanguage == "ja") s += 50
        if (item.genreIds.contains(16)) s += 80
        if (format == "MOVIE" && item.media_type == "movie") s += 40
        if (format != "MOVIE" && item.media_type == "tv") s += 40
        val y = (item.first_air_date ?: item.release_date)?.take(4)?.toIntOrNull()
        if (y != null && y == ani.seasonYear) s += 30
        s
    }

    private suspend fun buildSeriesResponse(
        tmdbId: Int?,
        metadata: AnimeMetadataAggregator.AggregatedMetadata,
        ani: AniListMedia,
        sourceUrl: String,
        aniZip: AniZipResponse?
    ): LoadResponse {

        val episodes = if (tmdbId != null) {
            val tmdbEp = loadTmdbEpisodes(tmdbId, metadata, aniZip)
            if (tmdbEp.isNotEmpty()) tmdbEp else buildAniListEpisodes(ani, metadata, tmdbId, aniZip)
        } else {
            buildAniListEpisodes(ani, metadata, null, aniZip)
        }

        return newAnimeLoadResponse(metadata.title ?: "Unknown", sourceUrl, TvType.Anime) {
            addEpisodes(DubStatus.Subbed, episodes)
            posterUrl = metadata.poster
            backgroundPosterUrl = metadata.backdrop
            logoUrl = metadata.logo
            plot = buildPlot(metadata)
            tags = metadata.genres
            year = metadata.year
            contentRating = metadata.certification
            duration = metadata.runtime
            actors = metadata.cast.map { ActorData(Actor(it.name, it.image), roleString = it.role) }
            showStatus = when (metadata.status) {
                "Returning Series", "In Production", "RELEASING" -> ShowStatus.Ongoing
                "Ended", "FINISHED" -> ShowStatus.Completed
                else -> null
            }
            val sc = metadata.imdbRating ?: metadata.tmdbRating
            if (sc != null) score = (sc * 1000).toInt()
            addImdbId(metadata.imdbId)
            addAniListId(metadata.anilistId)
            addMalId(metadata.malId)
            metadata.trailer?.let { addTrailer(it) }
        }
    }

    private suspend fun loadTmdbEpisodes(
        tmdbId: Int,
        metadata: AnimeMetadataAggregator.AggregatedMetadata,
        aniZip: AniZipResponse?
    ): List<Episode> {
        val series = app.get("${ApiConstants.TMDB_BASE}/tv/$tmdbId?api_key=${ApiConstants.TMDB_KEY}")
            .parsed<TmdbDetails>() ?: return emptyList()
        val seasonCount = series.number_of_seasons ?: return emptyList()
        val episodes = mutableListOf<Episode>()
        var absEp = 1

        for (s in 1..seasonCount) {
            val season = app.get("${ApiConstants.TMDB_BASE}/tv/$tmdbId/season/$s?api_key=${ApiConstants.TMDB_KEY}")
                .parsed<TmdbSeasonResponse>() ?: continue
            season.episodes.forEach { ep ->
                val zipEp = aniZip?.episodes?.get(absEp.toString())
                val epScore = ep.vote_average ?: zipEp?.rating?.toDoubleOrNull()
                episodes.add(newEpisode(
                    LoadLinksData(
                        title = metadata.title ?: "Unknown",
                        id = metadata.imdbId ?: tmdbId.toString(),
                        tmdbId = tmdbId, tvtype = "tv",
                        year = metadata.year?.toString(),
                        season = ep.season_number, episode = ep.episode_number,
                        firstAired = ep.air_date,
                        isAnime = true, isCartoon = true,
                        imdb_id = metadata.imdbId,
                        anilistId = metadata.anilistId,
                        malId = metadata.malId,
                        kitsuId = metadata.kitsuId?.toString(),
                        orgTitle = metadata.originalTitle,
                        airedYear = metadata.year
                    ).toJson()
                ) {
                    this.season = ep.season_number
                    this.episode = ep.episode_number
                    this.name = ep.name?.takeIf { it.isNotBlank() }
                        ?: zipEp?.title?.get("en")
                        ?: zipEp?.title?.get("x-jat")
                        ?: "Episode $absEp"
                    this.description = ep.overview?.takeIf { it.isNotBlank() } ?: zipEp?.overview
                    this.posterUrl = ep.still_path?.let { "${ApiConstants.TMDB_BACKDROP}$it" } ?: zipEp?.image
                    if (epScore != null) this.score = (epScore * 1000).toInt()
                    this.runTime = ep.runtime ?: zipEp?.runtime
                    addDate(ep.air_date)
                }
                absEp++
            }
        }
        return episodes
    }

    private fun buildAniListEpisodes(
        ani: AniListMedia,
        metadata: AnimeMetadataAggregator.AggregatedMetadata,
        tmdbId: Int?,
        aniZip: AniZipResponse?
    ): List<Episode> {
        val total = ani.episodes
            ?: (ani.nextAiringEpisode?.episode?.minus(1) ?: 0).coerceAtLeast(0)
        if (total == 0) return emptyList()

        return (1..total).map { ep ->
            val zipEp = aniZip?.episodes?.get(ep.toString())
            val zipScore = zipEp?.rating?.toDoubleOrNull()
            newEpisode(
                LoadLinksData(
                    title = metadata.title ?: "Unknown",
                    id = metadata.imdbId ?: (tmdbId?.toString() ?: ani.id.toString()),
                    tmdbId = tmdbId, tvtype = "tv",
                    year = ani.seasonYear?.toString(),
                    season = 1, episode = ep,
                    isAnime = true, isCartoon = true,
                    imdb_id = metadata.imdbId,
                    anilistId = ani.id, malId = ani.idMal,
                    kitsuId = metadata.kitsuId?.toString(),
                    orgTitle = metadata.originalTitle,
                    airedYear = ani.seasonYear
                ).toJson()
            ) {
                season = 1
                episode = ep
                name = zipEp?.title?.get("en")
                    ?: zipEp?.title?.get("x-jat")
                    ?: zipEp?.title?.get("ja")
                    ?: "Episode $ep"
                description = zipEp?.overview
                posterUrl = zipEp?.image
                    ?: ani.coverImage?.extraLarge
                    ?: ani.coverImage?.large
                runTime = zipEp?.runtime
                if (zipScore != null) score = (zipScore * 1000).toInt()
            }
        }
    }

    private suspend fun getAniListMedia(id: Int): AniListMedia? {
        val query = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                id idMal title { romaji english native }
                description averageScore seasonYear episodes bannerImage genres
                format duration status countryOfOrigin
                nextAiringEpisode { airingAt episode }
                coverImage { extraLarge large medium }
                trailer { id site } startDate { year month day }
                characters { edges { role node { name { full } image { large } }
                  voiceActors(language: JAPANESE) { name { full } image { large } } } }
              }
            }
        """.trimIndent()
        val json = runCatching {
            app.post(ApiConstants.ANILIST_API,
                json = mapOf("query" to query, "variables" to mapOf("id" to id))).text
        }.getOrNull() ?: return null
        return tryParseJson<AniListResponse>(json)?.data?.Media
    }

    // ═══════════════════ SOURCE RESOLVERS ═══════════════════
    private suspend fun resolveAniListIdFromMal(malId: Int?): Int? {
        if (malId == null) return null
        val q = """query { Media(idMal: $malId, type: ANIME) { id } }"""
        return runCatching {
            app.post(ApiConstants.ANILIST_API, json = mapOf("query" to q))
                .parsed<AniListResponse>()?.data?.Media?.id
        }.getOrNull()
    }

    private suspend fun resolveAniListIdFromKitsu(kitsuId: Int?): Int? {
        if (kitsuId == null) return null
        return runCatching {
            val json = app.get("${ApiConstants.ANIZIP_API}/mappings?kitsu_id=$kitsuId").text
            val root = tryParseJson<Map<String, Any>>(json) ?: return null
            val m = root["mappings"] as? Map<*, *> ?: return null
            (m["anilist_id"] as? Number)?.toInt()
        }.getOrNull()
    }

    private suspend fun resolveAniListIdFromTmdb(tmdbId: Int): Int? = runCatching {
        val tmdb = app.get(
            "${ApiConstants.TMDB_BASE}/movie/$tmdbId?api_key=${ApiConstants.TMDB_KEY}&append_to_response=external_ids"
        ).parsed<TmdbDetails>()
        val imdbId = tmdb?.external_ids?.imdbId ?: return null
        app.get("${ApiConstants.HAGLUND_BASE}/ids?source=imdb&id=$imdbId")
            .parsed<ExternalIdsResponse>()?.anilist
    }.getOrNull()

    // ═══════════════════ LOAD LINKS (STUB) ═══════════════════
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // TODO: Add your extraction logic here (each source call must invoke callback(link))
        val req = parseJson<LoadLinksData>(data) ?: return false
        // Example: callback(ExtractorLink("SourceName", "https://...", ...))
        return false
    }
}
