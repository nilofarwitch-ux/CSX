package com.hindi

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.Score
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Calendar
import java.util.Collections

// ═══════════════════ HELPERS ═══════════════════
fun getCurrentAniListSeason(): String {
    return when (Calendar.getInstance().get(Calendar.MONTH)) {
        Calendar.DECEMBER, Calendar.JANUARY, Calendar.FEBRUARY -> "WINTER"
        Calendar.MARCH, Calendar.APRIL, Calendar.MAY -> "SPRING"
        Calendar.JUNE, Calendar.JULY, Calendar.AUGUST -> "SUMMER"
        else -> "FALL"
    }
}

fun getCurrentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

private val aniZipMapper = jacksonObjectMapper()

suspend fun fetchAniZip(anilistId: Int? = null, kitsuId: Int? = null): AniZipResponse? {
    val query = when {
        anilistId != null -> "anilist_id=$anilistId"
        kitsuId != null -> "kitsu_id=$kitsuId"
        else -> return null
    }
    return runCatching {
        val json = app.get("${ApiConstants.ANIZIP_API}/mappings?$query").text
        aniZipMapper.readValue(json, AniZipResponse::class.java)
    }.getOrNull()
}

// ═══════════════════ DEDUP TRACKER ═══════════════════
object HomepageDedupTracker {
    private val seenKeys: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private var lastAccessTime: Long = 0L
    private const val SESSION_GAP_MS = 30 * 60 * 1000L

    @Synchronized
    fun startIfNewSession() {
        val now = System.currentTimeMillis()
        if (now - lastAccessTime > SESSION_GAP_MS) seenKeys.clear()
        lastAccessTime = now
    }

    fun tryClaim(title: String?, year: Int?, format: String?, sourceId: String?): Boolean {
        if (!sourceId.isNullOrBlank()) {
            if (!seenKeys.add("id_$sourceId")) return false
        }
        val titleKey = buildTitleKey(title, year, format) ?: return true
        return seenKeys.add(titleKey)
    }

    private fun buildTitleKey(title: String?, year: Int?, format: String?): String? {
        if (title.isNullOrBlank()) return null
        val clean = title.lowercase().replace(Regex("[^a-z0-9]"), "").trim()
        if (clean.isEmpty()) return null
        return "title_${clean}_${year ?: 0}_${normalizeFormat(format)}"
    }

    fun normalizeFormat(format: String?): String = when (format?.uppercase()) {
        "TV", "SERIES", "TV_SERIES" -> "TV"
        "TV_SHORT" -> "TV_SHORT"
        "MOVIE", "FILM" -> "MOVIE"
        "OVA" -> "OVA"
        "ONA" -> "ONA"
        "SPECIAL" -> "SPECIAL"
        "MUSIC" -> "MUSIC"
        else -> "TV"
    }
}

// ═══════════════════ HOMEPAGE FETCHER ═══════════════════
data class CategoryItem(
    val title: String,
    val year: Int?,
    val format: String,
    val sourceApi: String,
    val sourceId: String,
    val response: SearchResponse
)

object HomePageFetcher {

    private const val ANILIST_PER_PAGE = 30
    private const val JIKAN_PER_PAGE = 25
    private const val KITSU_PER_PAGE = 30

    suspend fun fetch(page: Int, categoryKey: String): List<CategoryItem> = when {
        categoryKey.startsWith("anilist_") -> fetchAniList(page, categoryKey)
        categoryKey.startsWith("jikan_")   -> fetchJikan(page, categoryKey)
        categoryKey.startsWith("kitsu_")   -> fetchKitsu(page, categoryKey)
        categoryKey.startsWith("tmdb_")    -> fetchTmdb(page, categoryKey)
        else -> emptyList()
    }

    // ─── AniList ───
    private suspend fun fetchAniList(page: Int, key: String): List<CategoryItem> {
        val season = getCurrentAniListSeason()
        val year = getCurrentYear()
        val args = when (key) {
            "anilist_trending"  -> "type: ANIME, sort: TRENDING_DESC"
            "anilist_season"    -> "type: ANIME, season: $season, seasonYear: $year, sort: POPULARITY_DESC"
            "anilist_top"       -> "type: ANIME, sort: SCORE_DESC"
            "anilist_upcoming"  -> "type: ANIME, status: NOT_YET_RELEASED, sort: POPULARITY_DESC"
            "anilist_airing"    -> "type: ANIME, status: RELEASING, sort: POPULARITY_DESC"
            "anilist_movies"    -> "type: ANIME, format: MOVIE, sort: POPULARITY_DESC"
            "anilist_ova"       -> "type: ANIME, format: OVA, sort: POPULARITY_DESC"
            "anilist_ona"       -> "type: ANIME, format: ONA, sort: POPULARITY_DESC"
            "anilist_action"    -> "type: ANIME, genre: \"Action\", sort: POPULARITY_DESC"
            "anilist_comedy"    -> "type: ANIME, genre: \"Comedy\", sort: POPULARITY_DESC"
            "anilist_romance"   -> "type: ANIME, genre: \"Romance\", sort: POPULARITY_DESC"
            "anilist_fantasy"   -> "type: ANIME, genre: \"Fantasy\", sort: POPULARITY_DESC"
            "anilist_scifi"     -> "type: ANIME, genre: \"Sci-Fi\", sort: POPULARITY_DESC"
            "anilist_horror"    -> "type: ANIME, genre: \"Horror\", sort: POPULARITY_DESC"
            "anilist_school"    -> "type: ANIME, tag: \"School\", sort: POPULARITY_DESC"
            "anilist_psych"     -> "type: ANIME, tag: \"Psychological\", sort: POPULARITY_DESC"
            "anilist_isekai"    -> "type: ANIME, tag: \"Isekai\", sort: POPULARITY_DESC"
            "anilist_shounen"   -> "type: ANIME, tag: \"Shounen\", sort: POPULARITY_DESC"
            "anilist_hidden"    -> "type: ANIME, averageScore_greater: 80, popularity_lesser: 50000, sort: SCORE_DESC"
            else -> return emptyList()
        }
        val gql = """
            query { Page(page: $page, perPage: $ANILIST_PER_PAGE) {
              media($args) { id format seasonYear averageScore title { romaji english native } coverImage { extraLarge } }
            } }
        """.trimIndent()

        val json = runCatching {
            app.post(ApiConstants.ANILIST_API, json = mapOf("query" to gql)).text
        }.getOrNull() ?: return emptyList()

        val media = tryParseJson<Map<String, Any>>(json)
            ?.let { it["data"] as? Map<*, *> }
            ?.let { it["Page"] as? Map<*, *> }
            ?.let { it["media"] as? List<Map<String, Any>> }
            ?: return emptyList()

        return media.mapNotNull { item ->
            val id = (item["id"] as? Number)?.toInt() ?: return@mapNotNull null
            val rawFormat = item["format"]?.toString() ?: "TV"
            val t = item["title"] as? Map<*, *>
            val title = t?.get("english")?.toString() ?: t?.get("romaji")?.toString()
                ?: t?.get("native")?.toString() ?: return@mapNotNull null
            val poster = ((item["coverImage"] as? Map<*, *>)?.get("extraLarge"))?.toString()
            val yr = (item["seasonYear"] as? Number)?.toInt()
            val avgScore = (item["averageScore"] as? Number)?.toDouble()
            val resp = newAnimeSearchResponse(
                title,
                AniData(id = id, source = "anilist", format = rawFormat).toJson(),
                if (rawFormat == "MOVIE") TvType.AnimeMovie else TvType.Anime
            ) {
                posterUrl = poster
                this.year = yr
                avgScore?.let { score = Score.from10(it / 10.0) }
            }
            CategoryItem(title, yr, HomepageDedupTracker.normalizeFormat(rawFormat),
                "anilist", "anilist_$id", resp)
        }
    }

    // ─── Jikan ───
    private suspend fun fetchJikan(page: Int, key: String): List<CategoryItem> {
        val endpoint = when (key) {
            "jikan_favorites" -> "${ApiConstants.JIKAN_API}/top/anime?filter=favorite&page=$page&limit=$JIKAN_PER_PAGE"
            "jikan_members"   -> "${ApiConstants.JIKAN_API}/top/anime?filter=bypopularity&page=$page&limit=$JIKAN_PER_PAGE"
            "jikan_airing"    -> "${ApiConstants.JIKAN_API}/top/anime?filter=airing&page=$page&limit=$JIKAN_PER_PAGE"
            else -> return emptyList()
        }
        delay(340) // Jikan: 3 req/sec safety
        val json = runCatching { app.get(endpoint).text }.getOrNull() ?: return emptyList()
        val data = tryParseJson<Map<String, Any>>(json)?.get("data") as? List<Map<String, Any>>
            ?: return emptyList()

        return data.mapNotNull { item ->
            val malId = (item["mal_id"] as? Number)?.toInt() ?: return@mapNotNull null
            val title = item["title_english"]?.toString()?.takeIf { it != "null" }
                ?: item["title"]?.toString() ?: return@mapNotNull null
            val poster = ((item["images"] as? Map<*, *>)?.get("jpg") as? Map<*, *>)
                ?.get("large_image_url")?.toString()
            val yr = (item["year"] as? Number)?.toInt()
            val rawType = item["type"]?.toString() ?: "TV"
            val scoreVal = (item["score"] as? Number)?.toDouble()
            val resp = newAnimeSearchResponse(
                title,
                AniData(id = malId, source = "jikan", format = rawType).toJson(),
                if (rawType == "Movie") TvType.AnimeMovie else TvType.Anime
            ) {
                posterUrl = poster
                this.year = yr
                scoreVal?.let { score = Score.from10(it) }
            }
            CategoryItem(title, yr, HomepageDedupTracker.normalizeFormat(rawType),
                "jikan", "mal_$malId", resp)
        }
    }

    // ─── Kitsu ───
    private suspend fun fetchKitsu(page: Int, key: String): List<CategoryItem> {
        val offset = (page - 1) * KITSU_PER_PAGE
        val url = when (key) {
            "kitsu_trending" -> "${ApiConstants.KITSU_EDGE_API}/anime?sort=-userCount&page%5Blimit%5D=$KITSU_PER_PAGE&page%5Boffset%5D=$offset"
            "kitsu_top"      -> "${ApiConstants.KITSU_EDGE_API}/anime?sort=-averageRating&page%5Blimit%5D=$KITSU_PER_PAGE&page%5Boffset%5D=$offset"
            "kitsu_airing"   -> "${ApiConstants.KITSU_EDGE_API}/anime?filter%5Bstatus%5D=current&sort=-userCount&page%5Blimit%5D=$KITSU_PER_PAGE&page%5Boffset%5D=$offset"
            else -> return emptyList()
        }
        val json = runCatching { app.get(url).text }.getOrNull() ?: return emptyList()
        val data = tryParseJson<Map<String, Any>>(json)?.get("data") as? List<Map<String, Any>>
            ?: return emptyList()

        return data.mapNotNull { item ->
            val kitsuId = (item["id"] as? String)?.toIntOrNull() ?: return@mapNotNull null
            val attrs = item["attributes"] as? Map<*, *> ?: return@mapNotNull null
            val titles = attrs["titles"] as? Map<*, *>
            val title = attrs["canonicalTitle"]?.toString()
                ?: titles?.get("en")?.toString()
                ?: titles?.get("en_jp")?.toString() ?: return@mapNotNull null
            val poster = (attrs["posterImage"] as? Map<*, *>)
                ?.let { (it["medium"] ?: it["small"])?.toString() }
            val rawType = attrs["subtype"]?.toString() ?: "TV"
            val yr = attrs["startDate"]?.toString()?.take(4)?.toIntOrNull()
            val avg = (attrs["averageRating"] as? String)?.toDoubleOrNull()?.div(10.0)
            val resp = newAnimeSearchResponse(
                title,
                AniData(id = kitsuId, source = "kitsu", format = rawType).toJson(),
                if (rawType.equals("movie", true)) TvType.AnimeMovie else TvType.Anime
            ) {
                posterUrl = poster
                this.year = yr
                avg?.let { score = Score.from10(it) }
            }
            CategoryItem(title, yr, HomepageDedupTracker.normalizeFormat(rawType),
                "kitsu", "kitsu_$kitsuId", resp)
        }
    }

    // ─── TMDB ───
    private suspend fun fetchTmdb(page: Int, key: String): List<CategoryItem> {
        val base = "?api_key=${ApiConstants.TMDB_KEY}&with_original_language=ja&page=$page"
        val url = when (key) {
            "tmdb_grossing"  -> "${ApiConstants.TMDB_BASE}/discover/movie$base&sort_by=revenue.desc"
            "tmdb_hindi_dub" -> "${ApiConstants.TMDB_BASE}/discover/movie$base&with_spoken_languages=hi&sort_by=popularity.desc"
            "tmdb_recent"    -> {
                val thirty = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(java.util.Date(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000))
                "${ApiConstants.TMDB_BASE}/discover/movie$base&primary_release_date.gte=$thirty&sort_by=primary_release_date.desc"
            }
            else -> return emptyList()
        }
        val json = runCatching { app.get(url).text }.getOrNull() ?: return emptyList()
        val results = tryParseJson<Map<String, Any>>(json)?.get("results") as? List<Map<String, Any>>
            ?: return emptyList()

        return results.mapNotNull { item ->
            val tmdbId = (item["id"] as? Number)?.toInt() ?: return@mapNotNull null
            val title = item["title"]?.toString() ?: item["original_title"]?.toString()
                ?: return@mapNotNull null
            val poster = item["poster_path"]?.toString()?.let { "${ApiConstants.TMDB_POSTER}$it" }
            val yr = item["release_date"]?.toString()?.take(4)?.toIntOrNull()
            val rating = (item["vote_average"] as? Number)?.toDouble()
            val resp = newAnimeSearchResponse(
                title,
                AniData(id = tmdbId, source = "tmdb", format = "MOVIE").toJson(),
                TvType.AnimeMovie
            ) {
                posterUrl = poster
                this.year = yr
                rating?.let { score = Score.from10(it) }
            }
            CategoryItem(title, yr, "MOVIE", "tmdb", "tmdb_$tmdbId", resp)
        }
    }
}

// ═══════════════════ AGGREGATOR ═══════════════════
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
        preFetchedAniList: AniListMedia? = null
    ): AggregatedMetadata = coroutineScope {

        val tmdbDeferred     = async { fetchTmdb(tmdbId, mediaType) }
        val cinemetaDeferred = async { fetchCinemeta(imdbId, mediaType) }
        val idsDeferred      = async { fetchExternalIds(imdbId) }
        val aniListDeferred  = async {
            when {
                preFetchedAniList != null -> preFetchedAniList
                aniListId != null -> fetchAniList(aniListId)
                else -> null
            }
        }

        val tmdb     = tmdbDeferred.await()
        val cinemeta = cinemetaDeferred.await()
        val ids      = idsDeferred.await()
        val aniList  = aniListDeferred.await()

        val castDeferred      = async {
            buildTmdbCast(tmdb).ifEmpty {
                buildAniListCast(aniList).ifEmpty { fetchTvdbCast(imdbId, mediaType) }
            }
        }
        val providersDeferred = async { fetchTmdbWatchProviders(tmdbId, mediaType) }

        val cast      = castDeferred.await()
        val providers = providersDeferred.await()

        val isMovie = mediaType.equals("movie", true) || aniList?.format == "MOVIE"

        AggregatedMetadata(
            imdbId = imdbId ?: tmdb?.external_ids?.imdbId,
            tmdbId = tmdbId ?: tmdb?.id,
            anilistId = aniList?.id ?: ids?.anilist,
            malId = aniList?.idMal ?: ids?.myanimelist,
            kitsuId = ids?.kitsu,
            anidbId = ids?.anidb,
            tvdbId = ids?.thetvdb,
            title = aniList?.title?.english ?: tmdb?.title ?: tmdb?.name
                ?: aniList?.title?.romaji ?: cinemeta?.name,
            originalTitle = aniList?.title?.native ?: aniList?.title?.romaji
                ?: cinemeta?.aliases?.firstOrNull(),
            description = cleanHtml(aniList?.description)
                ?: tmdb?.overview?.takeIf { it.isNotBlank() }
                ?: cinemeta?.description?.takeIf { it.isNotBlank() },
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
            backdrop = tmdb?.backdrop_path?.let { "${ApiConstants.TMDB_BACKDROP}$it" }
                ?: aniList?.bannerImage ?: cinemeta?.background,
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
            app.get("${ApiConstants.CINEMETA_BASE}/meta/$type/$imdbId.json").parsed<CinemetaResponse>()?.meta
        }.getOrNull()
    }

    private suspend fun fetchExternalIds(imdbId: String?): ExternalIdsResponse? {
        if (imdbId.isNullOrBlank()) return null
        return runCatching {
            app.get("${ApiConstants.HAGLUND_BASE}/ids?source=imdb&id=$imdbId").parsed<ExternalIdsResponse>()
        }.getOrNull()
    }

    private suspend fun fetchAniList(aniListId: Int): AniListMedia? {
        val query = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                id idMal title { romaji english native }
                description averageScore popularity season seasonYear status format duration episodes
                countryOfOrigin source synonyms
                startDate { year month day } endDate { year month day }
                trailer { id site } nextAiringEpisode { airingAt episode }
                bannerImage coverImage { extraLarge large medium }
                genres studios { nodes { name } }
                characters { edges { role node { name { full } image { large } }
                  voiceActors(language: JAPANESE) { name { full } image { large } } } }
              }
            }
        """.trimIndent()
        return runCatching {
            app.post(ApiConstants.ANILIST_API,
                json = mapOf("query" to query, "variables" to mapOf("id" to aniListId)))
                .parsed<AniListResponse>()?.data?.Media
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
                    val arr = meta.optJSONObject("app_extras")?.optJSONArray("cast")
                        ?: return@async emptyList()
                    (0 until arr.length()).mapNotNull { i ->
                        val m = arr.optJSONObject(i) ?: return@mapNotNull null
                        val name = m.optString("name").takeIf { it.isNotBlank() && it != "null" }
                            ?: return@mapNotNull null
                        ActorData(
                            name = name,
                            role = m.optString("character").takeIf { it.isNotBlank() && it != "null" },
                            image = m.optString("photo").takeIf { it.isNotBlank() && it != "null" }
                        )
                    }
                }
            }.map { it.await() }.flatten().distinctBy { it.name }.take(20)
        }
    }

    private suspend fun fetchTmdbWatchProviders(tmdbId: Int?, mediaType: String): List<String> {
        if (tmdbId == null) return emptyList()
        val endpoint = if (mediaType.equals("movie", true)) "movie" else "tv"
        return runCatching {
            val json = app.get("${ApiConstants.TMDB_BASE}/$endpoint/$tmdbId/watch/providers?api_key=${ApiConstants.TMDB_KEY}").text
            val root = tryParseJson<Map<String, Any>>(json) ?: return emptyList()
            val results = root["results"] as? Map<*, *> ?: return emptyList()
            val region = results["IN"] as? Map<*, *> ?: results["US"] as? Map<*, *> ?: return emptyList()
            val flat = region["flatrate"] as? List<Map<String, Any>> ?: return emptyList()
            flat.mapNotNull { it["provider_name"]?.toString() }
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

    private fun mergeGenres(aniList: AniListMedia?, tmdb: TmdbDetails?, cinemeta: CinemetaMeta?): List<String> =
        buildSet {
            aniList?.genres?.let(::addAll)
            tmdb?.genres?.mapNotNull { it.name }?.let(::addAll)
            cinemeta?.genre?.let(::addAll)
            cinemeta?.genres?.let(::addAll)
        }.toList()

    private fun extractYear(aniList: AniListMedia?, tmdb: TmdbDetails?, cinemeta: CinemetaMeta?): Int? =
        aniList?.seasonYear ?: aniList?.startDate?.year
            ?: tmdb?.release_date?.substringBefore("-")?.toIntOrNull()
            ?: tmdb?.first_air_date?.substringBefore("-")?.toIntOrNull()
            ?: cinemeta?.year?.substringBefore("-")?.substringBefore("–")?.toIntOrNull()

    private fun buildCountries(aniList: AniListMedia?, tmdb: TmdbDetails?): List<CountryInfo> {
        val list = mutableListOf<CountryInfo>()
        tmdb?.production_countries?.forEach {
            list += CountryInfo(it.name.orEmpty(), it.iso_3166_1)
        }
        if (list.isEmpty()) aniList?.countryOfOrigin?.let { list += CountryInfo(it, it) }
        return list
    }

    private fun getCertification(tmdb: TmdbDetails?, mediaType: String): String? {
        val regions = listOf("IN", "US", "GB")
        return if (mediaType.equals("movie", true)) {
            regions.firstNotNullOfOrNull { code ->
                tmdb?.release_dates?.results?.firstOrNull { it.iso_3166_1 == code }
                    ?.release_dates?.firstOrNull()?.certification?.takeIf { it.isNotBlank() }
            }
        } else {
            regions.firstNotNullOfOrNull { code ->
                tmdb?.content_ratings?.results?.firstOrNull { it.iso_3166_1 == code }
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
            .replace("&quot;", "\"").replace("&#039;", "'")
            .replace("&amp;", "&").replace("&mdash;", "—")
            .replace("&ndash;", "–").replace("&nbsp;", " ")
            .replace("&lt;", "<").replace("&gt;", ">")
            .trim()
    }
}
