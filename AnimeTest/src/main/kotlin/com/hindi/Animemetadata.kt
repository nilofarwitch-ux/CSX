package com.hindi

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

object AnimeMetadataAggregator {

    data class CountryInfo(val name: String, val isoCode: String? = null)
    data class ActorData(val name: String, val role: String? = null, val image: String? = null)

    data class AggregatedMetadata(
        val imdbId: String? = null,
        val tmdbId: Int? = null,
        val anilistId: Int? = null,
        val malId: Int? = null,
        val kitsuId: Int? = null,
        val anidbId: Int? = null,
        val tvdbId: Int? = null,
        val title: String? = null,
        val originalTitle: String? = null,
        val description: String? = null,
        val poster: String? = null,
        val backdrop: String? = null,
        val logo: String? = null,
        val trailer: String? = null,
        val year: Int? = null,
        val runtime: Int? = null,
        val status: String? = null,
        val certification: String? = null,
        val genres: List<String> = emptyList(),
        val countries: List<CountryInfo> = emptyList(),
        val imdbRating: Double? = null,
        val tmdbRating: Double? = null,
        val anilistRating: Int? = null,
        val voteCount: Int? = null,
        val popularityScore: Double? = null,
        val awards: String? = null,
        val cast: List<ActorData> = emptyList(),
        val streamingProviders: List<String> = emptyList()
    )

    suspend fun aggregate(
        imdbId: String? = null,
        tmdbId: Int? = null,
        mediaType: String,
        title: String? = null,
        aniListId: Int? = null,
        preFetchedAniList: AniListMedia? = null,
        preFetchedAniZip: AniZipResponse? = null
    ): AggregatedMetadata = coroutineScope {

        // Parallel fetches
        val tmdbDeferred      = async { fetchTmdb(tmdbId, mediaType) }
        val cinemetaDeferred  = async { fetchCinemeta(imdbId, mediaType) }
        val idsDeferred       = async { fetchExternalIds(imdbId) }
        val aniListDeferred   = async {
            when {
                preFetchedAniList != null -> preFetchedAniList
                aniListId != null -> fetchAniList(aniListId = aniListId, title = null)
                else -> null
            }
        }

        val tmdb     = tmdbDeferred.await()
        val cinemeta = cinemetaDeferred.await()
        val ids      = idsDeferred.await()
        val aniList  = aniListDeferred.await()

        // Cast: TMDB → AniList VA → TVDB (last resort)
        val castDeferred = async {
            buildTmdbCast(tmdb).ifEmpty {
                buildAniListCast(aniList).ifEmpty {
                    fetchTvdbCast(imdbId, mediaType)
                }
            }
        }

        // Streaming providers (TMDB)
        val providersDeferred = async {
            fetchTmdbWatchProviders(tmdbId, mediaType)
        }

        val cast = castDeferred.await()
        val providers = providersDeferred.await()

        // Format detection for poster priority
        val isMovie = mediaType.equals("movie", true) ||
            aniList?.format == "MOVIE"

        AggregatedMetadata(
            imdbId = imdbId ?: tmdb?.external_ids?.imdbId,
            tmdbId = tmdbId ?: tmdb?.id,
            anilistId = aniList?.id ?: ids?.anilist,
            malId = aniList?.idMal ?: ids?.myanimelist,
            kitsuId = ids?.kitsu,
            anidbId = ids?.anidb,
            tvdbId = ids?.thetvdb,
            title = aniList?.title?.english
                ?: tmdb?.title ?: tmdb?.name
                ?: aniList?.title?.romaji
                ?: cinemeta?.name,
            originalTitle = aniList?.title?.native
                ?: aniList?.title?.romaji
                ?: cinemeta?.aliases?.firstOrNull(),
            description = cleanHtml(aniList?.description)
                ?: tmdb?.overview?.takeIf { it.isNotBlank() }
                ?: cinemeta?.description?.takeIf { it.isNotBlank() },
            // Poster: MOVIE → TMDB first, else AniList first
            poster = if (isMovie) {
                tmdb?.poster_path?.let { "${ApiConstants.TMDB_POSTER}$it" }
                    ?: aniList?.coverImage?.extraLarge
                    ?: aniList?.coverImage?.large
                    ?: cinemeta?.poster
            } else {
                aniList?.coverImage?.extraLarge
                    ?: aniList?.coverImage?.large
                    ?: tmdb?.poster_path?.let { "${ApiConstants.TMDB_POSTER}$it" }
                    ?: cinemeta?.poster
            },
            // Backdrop: TMDB first (cinematic 16:9)
            backdrop = tmdb?.backdrop_path?.let { "${ApiConstants.TMDB_BACKDROP}$it" }
                ?: aniList?.bannerImage
                ?: cinemeta?.background,
            logo = selectLogo(tmdb),
            trailer = selectTrailer(aniList, tmdb),
            year = extractYear(aniList, tmdb, cinemeta),
            runtime = if (isMovie) aniList?.duration ?: tmdb?.runtime else null,
            status = aniList?.status ?: tmdb?.status,
            certification = getCertification(tmdb, mediaType),
            genres = mergeGenres(aniList, tmdb, cinemeta),
            countries = buildCountries(aniList, tmdb),
            imdbRating = cinemeta?.imdbRating?.toDoubleOrNull(),
            tmdbRating = tmdb?.vote_average,
            anilistRating = aniList?.averageScore,
            voteCount = tmdb?.vote_count,
            popularityScore = calculatePopularity(aniList, tmdb, cinemeta),
            awards = cinemeta?.awards,
            cast = cast,
            streamingProviders = providers
        )
    }

    private suspend fun fetchTmdb(tmdbId: Int?, mediaType: String): TmdbDetails? {
        if (tmdbId == null) return null
        val endpoint = if (mediaType.equals("movie", true)) "movie" else "tv"
        val url = "${ApiConstants.TMDB_BASE}/$endpoint/$tmdbId?api_key=${ApiConstants.TMDB_KEY}" +
            "&append_to_response=credits,videos,images,external_ids,content_ratings,release_dates" +
            "&include_image_language=en,null"
        return runCatching { app.get(url).parsed<TmdbDetails>() }.getOrNull()
    }

    private suspend fun fetchCinemeta(imdbId: String?, mediaType: String): CinemetaMeta? {
        if (imdbId.isNullOrBlank()) return null
        val type = if (mediaType.equals("movie", true)) "movie" else "series"
        return runCatching {
            app.get("${ApiConstants.CINEMETA_BASE}/meta/$type/$imdbId.json")
                .parsed<CinemetaResponse>()?.meta
        }.getOrNull()
    }

    private suspend fun fetchExternalIds(imdbId: String?): ExternalIdsResponse? {
        if (imdbId.isNullOrBlank()) return null
        return runCatching {
            app.get("${ApiConstants.HAGLUND_BASE}/ids?source=imdb&id=$imdbId")
                .parsed<ExternalIdsResponse>()
        }.getOrNull()
    }

    private suspend fun fetchAniList(aniListId: Int? = null, title: String? = null): AniListMedia? {
        if (aniListId == null && title.isNullOrBlank()) return null
        val query = """
            query (${'$'}id: Int, ${'$'}search: String) {
              Media(id: ${'$'}id, search: ${'$'}search, type: ANIME) {
                id idMal
                title { romaji english native }
                description averageScore popularity season seasonYear status format duration episodes
                countryOfOrigin source synonyms
                startDate { year month day } endDate { year month day }
                trailer { id site } nextAiringEpisode { airingAt episode }
                bannerImage coverImage { extraLarge large medium }
                genres studios { nodes { name } }
                characters { edges { role node { name { full } image { large } } voiceActors(language: JAPANESE) { name { full } image { large } } } }
              }
            }
        """.trimIndent()
        val body = mapOf("query" to query, "variables" to mapOf("id" to aniListId, "search" to title))
        return runCatching {
            app.post(ApiConstants.ANILIST_API, json = body).parsed<AniListResponse>()?.data?.Media
        }.getOrNull()
    }

    private suspend fun fetchTvdbCast(imdbId: String?, mediaType: String): List<ActorData> {
        if (imdbId.isNullOrBlank()) return emptyList()
        val tvType = if (mediaType.equals("movie", true)) "movie" else "series"
        val urls = listOf(
            "https://aiometadata.elfhosted.com/stremio/9197a4a9-2f5b-4911-845e-8704c520bdf7/meta/$tvType/$imdbId.json",
            "https://94c8cb9f702d-tmdb-addon.baby-beamup.club/meta/$tvType/$imdbId.json"
        )
        return coroutineScope {
            urls.map { url ->
                async {
                    val body = runCatching { app.get(url, timeout = 10L).text }.getOrNull()
                    if (body.isNullOrBlank()) return@async emptyList<ActorData>()
                    val meta = runCatching { JSONObject(body) }.getOrNull()
                        ?.optJSONObject("meta") ?: return@async emptyList()
                    val castArray = meta.optJSONObject("app_extras")
                        ?.optJSONArray("cast") ?: return@async emptyList()
                    (0 until castArray.length()).mapNotNull { i ->
                        val m = castArray.optJSONObject(i) ?: return@mapNotNull null
                        val name = m.optString("name").takeIf { it.isNotBlank() && it != "null" }
                            ?: return@mapNotNull null
                        ActorData(
                            name = name,
                            role = m.optString("character").takeIf { it.isNotBlank() && it != "null" },
                            image = m.optString("photo").takeIf { it.isNotBlank() && it != "null" }
                        )
                    }
                }
            }.let { jobs -> jobs.map { it.await() } }
                .flatten().distinctBy { it.name }.take(20)
        }
    }

    private suspend fun fetchTmdbWatchProviders(tmdbId: Int?, mediaType: String): List<String> {
        if (tmdbId == null) return emptyList()
        val endpoint = if (mediaType.equals("movie", true)) "movie" else "tv"
        return runCatching {
            val json = app.get("${ApiConstants.TMDB_BASE}/$endpoint/$tmdbId/watch/providers?api_key=${ApiConstants.TMDB_KEY}").text
            val root = AppUtils.tryParseJson<Map<String, Any>>(json) ?: return emptyList()
            val results = root["results"] as? Map<*, *> ?: return emptyList()
            val region = results["IN"] as? Map<*, *>
                ?: results["US"] as? Map<*, *>
                ?: return emptyList()
            val flatrate = region["flatrate"] as? List<Map<String, Any>> ?: return emptyList()
            flatrate.mapNotNull { it["provider_name"]?.toString() }
        }.getOrElse { emptyList() }
    }

    private fun buildTmdbCast(tmdb: TmdbDetails?): List<ActorData> =
        tmdb?.credits?.cast?.take(20)?.mapNotNull {
            val name = it.name ?: return@mapNotNull null
            ActorData(name, it.character, it.profile_path?.let { p -> "${ApiConstants.TMDB_IMAGE}/w500$p" })
        } ?: emptyList()

    private fun buildAniListCast(aniList: AniListMedia?): List<ActorData> =
        aniList?.characters?.edges?.take(20)?.mapNotNull { edge ->
            val va = edge.voiceActors.firstOrNull()
            ActorData(
                name = va?.name?.full ?: return@mapNotNull null,
                role = edge.node?.name?.full,
                image = va.image?.large
            )
        } ?: emptyList()

    private fun selectLogo(tmdb: TmdbDetails?): String? {
        val best = tmdb?.images?.logos?.firstOrNull { it.iso_639_1 == "en" }
            ?: tmdb?.images?.logos?.firstOrNull { it.iso_639_1 == null }
            ?: tmdb?.images?.logos?.maxByOrNull { it.vote_average ?: 0.0 }
        return best?.file_path?.let { "${ApiConstants.TMDB_LOGO}$it" }
    }

    private fun selectTrailer(aniList: AniListMedia?, tmdb: TmdbDetails?): String? {
        aniList?.trailer?.let { t ->
            if (t.site.equals("youtube", true) && !t.id.isNullOrBlank())
                return "${ApiConstants.YOUTUBE_BASE}${t.id}"
        }
        val t = tmdb?.videos?.results?.firstOrNull {
            it.site.equals("YouTube", true) && it.type.equals("Trailer", true)
        }
        return t?.key?.let { "${ApiConstants.YOUTUBE_BASE}$it" }
    }

    private fun mergeGenres(aniList: AniListMedia?, tmdb: TmdbDetails?, cinemeta: CinemetaMeta?): List<String> = buildSet {
        aniList?.genres?.let(::addAll)
        tmdb?.genres?.mapNotNull { it.name }?.let(::addAll)
        cinemeta?.genre?.let(::addAll)
        cinemeta?.genres?.let(::addAll)
    }.toList()

    private fun extractYear(aniList: AniListMedia?, tmdb: TmdbDetails?, cinemeta: CinemetaMeta?): Int? =
        aniList?.seasonYear
            ?: aniList?.startDate?.year
            ?: tmdb?.release_date?.substringBefore("-")?.toIntOrNull()
            ?: tmdb?.first_air_date?.substringBefore("-")?.toIntOrNull()
            ?: cinemeta?.year?.substringBefore("-")?.substringBefore("–")?.toIntOrNull()

    private fun buildCountries(aniList: AniListMedia?, tmdb: TmdbDetails?): List<CountryInfo> {
        val list = mutableListOf<CountryInfo>()
        tmdb?.production_countries?.forEach {
            list += CountryInfo(it.name.orEmpty(), it.iso_3166_1)
        }
        if (list.isEmpty()) aniList?.countryOfOrigin?.let {
            list += CountryInfo(it, it)
        }
        return list
    }

    private fun getCertification(tmdb: TmdbDetails?, mediaType: String): String? {
        val regions = listOf("IN", "US", "GB")
        return if (mediaType.equals("movie", true)) {
            regions.firstNotNullOfOrNull { code ->
                tmdb?.release_dates?.results
                    ?.firstOrNull { it.iso_3166_1 == code }
                    ?.release_dates?.firstOrNull()?.certification?.takeIf { it.isNotBlank() }
            }
        } else {
            regions.firstNotNullOfOrNull { code ->
                tmdb?.content_ratings?.results
                    ?.firstOrNull { it.iso_3166_1 == code }
                    ?.rating?.takeIf { it.isNotBlank() }
            }
        }
    }

    private fun calculatePopularity(aniList: AniListMedia?, tmdb: TmdbDetails?, cinemeta: CinemetaMeta?): Double {
        var s = 0.0
        aniList?.averageScore?.let { s += it }
        aniList?.popularity?.let { s += it / 1000.0 }
        tmdb?.vote_average?.let { s += it * 5 }
        tmdb?.vote_count?.let { s += it.coerceAtMost(50000) / 1000.0 }
        cinemeta?.imdbRating?.toDoubleOrNull()?.let { s += it * 10 }
        return s
    }

    private fun cleanHtml(input: String?): String? {
        if (input.isNullOrBlank()) return null
        return input
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }
}
