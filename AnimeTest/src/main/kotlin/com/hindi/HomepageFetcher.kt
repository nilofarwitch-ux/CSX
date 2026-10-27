package com.hindi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.Score
import kotlinx.coroutines.delay
import java.util.Calendar

fun getCurrentAniListSeason(): String {
    val month = Calendar.getInstance().get(Calendar.MONTH)
    return when (month) {
        Calendar.DECEMBER, Calendar.JANUARY, Calendar.FEBRUARY -> "WINTER"
        Calendar.MARCH, Calendar.APRIL, Calendar.MAY -> "SPRING"
        Calendar.JUNE, Calendar.JULY, Calendar.AUGUST -> "SUMMER"
        Calendar.SEPTEMBER, Calendar.OCTOBER, Calendar.NOVEMBER -> "FALL"
        else -> "WINTER"
    }
}

fun getCurrentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

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

    private val currentSeason: String get() = getCurrentAniListSeason()
    private val currentYear: Int get() = getCurrentYear()

    suspend fun fetch(page: Int, categoryKey: String): List<CategoryItem> {
        return when {
            categoryKey.startsWith("anilist_") -> fetchAniList(page, categoryKey)
            categoryKey.startsWith("jikan_")   -> fetchJikan(page, categoryKey)
            categoryKey.startsWith("kitsu_")   -> fetchKitsu(page, categoryKey)
            categoryKey.startsWith("tmdb_")    -> fetchTmdb(page, categoryKey)
            else -> emptyList()
        }
    }

    // ═══════════════════ ANILIST ═══════════════════
    private suspend fun fetchAniList(page: Int, key: String): List<CategoryItem> {
        val args = when (key) {
            "anilist_trending"  -> "type: ANIME, sort: TRENDING_DESC"
            "anilist_season"    -> "type: ANIME, season: $currentSeason, seasonYear: $currentYear, sort: POPULARITY_DESC"
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
            query {
              Page(page: $page, perPage: $ANILIST_PER_PAGE) {
                media($args) {
                  id format seasonYear averageScore
                  title { romaji english native }
                  coverImage { extraLarge }
                }
              }
            }
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
            val titleObj = item["title"] as? Map<*, *>
            val title = titleObj?.get("english")?.toString()
                ?: titleObj?.get("romaji")?.toString()
                ?: titleObj?.get("native")?.toString()
                ?: return@mapNotNull null
            val poster = ((item["coverImage"] as? Map<*, *>)?.get("extraLarge"))?.toString()
            val year = (item["seasonYear"] as? Number)?.toInt()
            val avgScore = (item["averageScore"] as? Number)?.toDouble()

            val response = newAnimeSearchResponse(
                title,
                AniData(id = id, source = "anilist", format = rawFormat).toJson(),
                if (rawFormat == "MOVIE") TvType.AnimeMovie else TvType.Anime
            ) {
                posterUrl = poster
                this.year = year
                avgScore?.let { score = Score.from10(it / 10.0) }
            }

            CategoryItem(title, year, HomepageDedupTracker.normalizeFormat(rawFormat),
                "anilist", "anilist_$id", response)
        }
    }

    // ═══════════════════ JIKAN ═══════════════════
    private suspend fun fetchJikan(page: Int, key: String): List<CategoryItem> {
        val endpoint = when (key) {
            "jikan_favorites" -> "${ApiConstants.JIKAN_API}/top/anime?filter=favorite&page=$page&limit=$JIKAN_PER_PAGE"
            "jikan_members"   -> "${ApiConstants.JIKAN_API}/top/anime?filter=bypopularity&page=$page&limit=$JIKAN_PER_PAGE"
            "jikan_airing"    -> "${ApiConstants.JIKAN_API}/top/anime?filter=airing&page=$page&limit=$JIKAN_PER_PAGE"
            else -> return emptyList()
        }

        delay(340) // Jikan: 3 req/sec safety
        val json = runCatching { app.get(endpoint).text }.getOrNull() ?: return emptyList()
        val data = tryParseJson<Map<String, Any>>(json)
            ?.get("data") as? List<Map<String, Any>> ?: return emptyList()

        return data.mapNotNull { item ->
            val malId = (item["mal_id"] as? Number)?.toInt() ?: return@mapNotNull null
            val title = item["title_english"]?.toString()?.takeIf { it != "null" }
                ?: item["title"]?.toString()
                ?: return@mapNotNull null
            val poster = ((item["images"] as? Map<*, *>)?.get("jpg") as? Map<*, *>)
                ?.get("large_image_url")?.toString()
            val year = (item["year"] as? Number)?.toInt()
            val rawType = item["type"]?.toString() ?: "TV"
            val scoreVal = (item["score"] as? Number)?.toDouble()

            val response = newAnimeSearchResponse(
                title,
                AniData(id = malId, source = "jikan", format = rawType).toJson(),
                if (rawType == "Movie") TvType.AnimeMovie else TvType.Anime
            ) {
                posterUrl = poster
                this.year = year
                scoreVal?.let { score = Score.from10(it) }
            }

            CategoryItem(title, year, HomepageDedupTracker.normalizeFormat(rawType),
                "jikan", "mal_$malId", response)
        }
    }

    // ═══════════════════ KITSU ═══════════════════
    private suspend fun fetchKitsu(page: Int, key: String): List<CategoryItem> {
        val offset = (page - 1) * KITSU_PER_PAGE
        val url = when (key) {
            "kitsu_trending" -> "${ApiConstants.KITSU_EDGE_API}/anime?sort=-userCount&page%5Blimit%5D=$KITSU_PER_PAGE&page%5Boffset%5D=$offset"
            "kitsu_top"      -> "${ApiConstants.KITSU_EDGE_API}/anime?sort=-averageRating&page%5Blimit%5D=$KITSU_PER_PAGE&page%5Boffset%5D=$offset"
            "kitsu_airing"   -> "${ApiConstants.KITSU_EDGE_API}/anime?filter%5Bstatus%5D=current&sort=-userCount&page%5Blimit%5D=$KITSU_PER_PAGE&page%5Boffset%5D=$offset"
            else -> return emptyList()
        }

        val json = runCatching { app.get(url).text }.getOrNull() ?: return emptyList()
        val data = tryParseJson<Map<String, Any>>(json)
            ?.get("data") as? List<Map<String, Any>> ?: return emptyList()

        return data.mapNotNull { item ->
            val kitsuId = (item["id"] as? String)?.toIntOrNull() ?: return@mapNotNull null
            val attrs = item["attributes"] as? Map<*, *> ?: return@mapNotNull null
            val titles = attrs["titles"] as? Map<*, *>
            val title = attrs["canonicalTitle"]?.toString()
                ?: titles?.get("en")?.toString()
                ?: titles?.get("en_jp")?.toString()
                ?: return@mapNotNull null
            val poster = (attrs["posterImage"] as? Map<*, *>)
                ?.let { (it["medium"] ?: it["small"])?.toString() }
            val rawType = attrs["subtype"]?.toString() ?: "TV"
            val year = attrs["startDate"]?.toString()?.take(4)?.toIntOrNull()
            val avgRating = (attrs["averageRating"] as? String)?.toDoubleOrNull()?.div(10.0)

            val response = newAnimeSearchResponse(
                title,
                AniData(id = kitsuId, source = "kitsu", format = rawType).toJson(),
                if (rawType.equals("movie", true)) TvType.AnimeMovie else TvType.Anime
            ) {
                posterUrl = poster
                this.year = year
                avgRating?.let { score = Score.from10(it) }
            }

            CategoryItem(title, year, HomepageDedupTracker.normalizeFormat(rawType),
                "kitsu", "kitsu_$kitsuId", response)
        }
    }

    // ═══════════════════ TMDB ═══════════════════
    private suspend fun fetchTmdb(page: Int, key: String): List<CategoryItem> {
        val base = "?api_key=${ApiConstants.TMDB_KEY}&with_original_language=ja&page=$page"
        val url = when (key) {
            "tmdb_grossing" -> "${ApiConstants.TMDB_BASE}/discover/movie$base&sort_by=revenue.desc"
            "tmdb_hindi_dub" -> "${ApiConstants.TMDB_BASE}/discover/movie$base&with_spoken_languages=hi&sort_by=popularity.desc"
            "tmdb_recent" -> {
                val thirtyDaysAgo = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(java.util.Date(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000))
                "${ApiConstants.TMDB_BASE}/discover/movie$base&primary_release_date.gte=$thirtyDaysAgo&sort_by=primary_release_date.desc"
            }
            else -> return emptyList()
        }

        val json = runCatching { app.get(url).text }.getOrNull() ?: return emptyList()
        val results = tryParseJson<Map<String, Any>>(json)
            ?.get("results") as? List<Map<String, Any>> ?: return emptyList()

        return results.mapNotNull { item ->
            val tmdbId = (item["id"] as? Number)?.toInt() ?: return@mapNotNull null
            val title = item["title"]?.toString()
                ?: item["original_title"]?.toString()
                ?: return@mapNotNull null
            val poster = item["poster_path"]?.toString()?.let { "${ApiConstants.TMDB_POSTER}$it" }
            val year = item["release_date"]?.toString()?.take(4)?.toIntOrNull()
            val rating = (item["vote_average"] as? Number)?.toDouble()

            val response = newAnimeSearchResponse(
                title,
                AniData(id = tmdbId, source = "tmdb", format = "MOVIE").toJson(),
                TvType.AnimeMovie
            ) {
                posterUrl = poster
                this.year = year
                rating?.let { score = Score.from10(it) }
            }

            CategoryItem(title, year, "MOVIE", "tmdb", "tmdb_$tmdbId", response)
        }
    }
}
