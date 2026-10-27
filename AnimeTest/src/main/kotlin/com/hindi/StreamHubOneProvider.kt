package com.hindi

import com.hindi.DesiExtensionPopup
import com.hindi.providers.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import kotlin.random.Random


data class HybridData(
    val tmdbId: Int? = null,
    val cinemetaId: String? = null,
    val mediaType: String,
    val source: String,
    val title: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val plot: String? = null,
    val year: Int? = null,
    val genres: List<String>? = null,
    val imdbId: String? = null,
    val episodes: List<HybridStremioEpisode>? = null,
    val stremioBaseUrl: String? = null,
    val stremioType: String? = null,
    val stremioId: String? = null
)

data class HybridStremioEpisode(
    val season: Int? = null,
    val episode: Int? = null,
    val title: String? = null,
    val overview: String? = null,
    val thumbnail: String? = null,
    val released: String? = null,
    val runtime: Int? = null
)

class StreamHubOneProvider : MainAPI() {

    override var name = "StreamHub One"
    override var mainUrl = "https://www.themoviedb.org"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.AsianDrama
    )

    private val cinemetaSkipMap = mutableMapOf<String, Int>()
    private val seenCinemetaIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val kitsuSkipMap = mutableMapOf<String, Int>()
    private val seenKitsuIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // =========================================================
    // HOME DEFINITIONS
    // =========================================================

    private data class HomeDefinition(
        val id: String,
        val name: String,
        val endpoint: String,
        val contentType: String
    )

    private fun getCurrentDate(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    private fun tmdbDefinitions(): List<HomeDefinition> {
    val today = getCurrentDate()

    val fiftyDaysAgo = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        .format(Date(System.currentTimeMillis() - 50L * 24 * 60 * 60 * 1000))
       

                return listOf(

            HomeDefinition(
                "tmdb_trending",
                "Trending",
                "trending/all/day",
                "Mixed"
            ),
            HomeDefinition(
                "tmdb_popular_movies",
                "Popular Movies",
                "trending/movie/week",
                "Movies"
            ),
            HomeDefinition(
                "tmdb_popular_tv",
                "Popular TV Shows",
                "trending/tv/week",
                "Series"
            ),
HomeDefinition(
                "tmdb_trending_indian_movies",
                "Trending Indian Movies",
                "discover/movie?with_origin_country=IN&sort_by=popularity.desc",
                "Movies"
            ),
HomeDefinition(
    "tmdb_local_series_new_releases",
    "Series · New Releases · LOCAL",
    "discover/tv" +
        "?with_origin_country=IN" +
        "&sort_by=first_air_date.desc" +
        "&include_null_first_air_dates=false",
    "Series"
),
HomeDefinition(
    "tmdb_local_movies_popular",
    "Movies · Popular · LOCAL",
    "discover/movie" +
        "?with_origin_country=IN" +
        "&sort_by=popularity.desc",
    "Movies"
),
HomeDefinition(
    "kitsu_latest_episodes",
    "Latest Release Episodes (Daily Update)",
    "${ApiConstants.KITSU_API}/catalog/anime/kitsu-anime-airing/skip=###",
    "Anime"
),
            HomeDefinition(
                "tmdb_airing_today_anime",
                "Airing Today Anime",
                "discover/tv?with_genres=16&with_origin_country=JP&air_date.lte=$today&air_date.gte=$today",
                "Anime"
            ),
            
            HomeDefinition(
                "tmdb_korean_dramas",
                "Korean Dramas",
                "discover/tv?with_original_language=ko&without_genres=10763,10764,10767&sort_by=popularity.desc",
                "Series"
            ),
            HomeDefinition(
                "tmdb_netflix",
                "Netflix",
                "discover/tv",
                "Series"
            ),
HomeDefinition(
                "tmdb_on_the_air_anime",
                "On The Air Anime",
                "discover/tv?with_genres=16&with_origin_country=JP",
                "Anime"
            ),
            HomeDefinition(
                "tmdb_prime_video",
                "Prime Video",
                "discover/tv",
                "Series"
            ),
            

            HomeDefinition(
                "tmdb_sonyliv",
                "SonyLIV",
                "discover/tv",
                "Series"
            ),

HomeDefinition(
    "kitsu_2026_anime",
    "2026 Anime",
    "kitsu_edge:2026",
    "Anime"
),
            HomeDefinition(
                "tmdb_zee5",
                "ZEE5",
                "discover/tv",
                "Series"
            ),

            HomeDefinition(
                "tmdb_disney_plus",
                "Disney+",
                "discover/tv?with_networks=2739",
                "Series"
            ),
            HomeDefinition(
                "tmdb_apple_tv_plus",
                "Apple TV+",
                "discover/tv?with_networks=2552",
                "Series"
            ),
            
HomeDefinition("tmdb_mxplayer", "Amazon MX Player", "discover/tv?with_watch_providers=1898|515&watch_region=IN", "Series"),

            HomeDefinition(
                "tmdb_paramount_plus",
                "Paramount+",
                "discover/tv",
                "Series"
            ),
            HomeDefinition(
                "tmdb_hulu",
                "Hulu",
                "discover/tv?with_networks=453",
                "Series"
            ),
            HomeDefinition(
                "tmdb_hbo",
                "HBO",
                "discover/tv?with_networks=49",
                "Series"
            ),
HomeDefinition(
    "kitsu_popular_anime",
    "Popular Anime",
    "${ApiConstants.KITSU_API}/catalog/anime/kitsu-anime-popular/skip=###",
    "Anime"
),
            
            HomeDefinition(
                "tmdb_sony",
                "Sony",
                "discover/tv",
                "Series"
            ),
            HomeDefinition(
                "tmdb_crunchyroll",
                "Crunchyroll",
                "discover/tv",
                "Series"
            ),
            HomeDefinition(
                "tmdb_bbc_series",
                "BBC Series",
                "discover/tv?with_networks=4&without_genres=10763,10764,10767&sort_by=popularity.desc",
                "Series"
            ),
            
            HomeDefinition(
                "tmdb_anime_movies",
                "Anime Movies",
                "discover/movie?with_keywords=210024|222243",
                "Anime"
            ),
            HomeDefinition(
                "tmdb_top_rated_tv",
                "Top Rated TV Shows",
                "tv/top_rated",
                "Series"
            ),
            HomeDefinition(
                "tmdb_top_rated_anime",
                "Top Rated Anime",
                "discover/tv?with_genres=16&with_origin_country=JP&sort_by=vote_average.desc&vote_count.gte=100",
                "Anime"
            ),
// =====================================================
// 6 LOCAL CATALOGS - INDIA
// =====================================================

HomeDefinition(
    "tmdb_local_movies_now_playing",
    "Movies · Now Playing · LOCAL",
    "discover/movie" +
        "?with_origin_country=IN" +
        "&region=IN" +
        "&primary_release_date.gte=$fiftyDaysAgo" +
        "&primary_release_date.lte=$today" +
        "&sort_by=popularity.desc",
    "Movies"
),


HomeDefinition(
    "tmdb_local_movies_top_rated",
    "Movies · Top Rated · LOCAL",
    "discover/movie" +
        "?with_origin_country=IN" +
        "&sort_by=vote_average.desc" +
        "&vote_count.gte=20",
    "Movies"
),



HomeDefinition(
    "tmdb_local_series_popular",
    "Series · Popular · LOCAL",
    "discover/tv" +
        "?with_origin_country=IN" +
        "&sort_by=popularity.desc" +
        "&include_null_first_air_dates=false",
    "Series"
),

HomeDefinition(
    "tmdb_local_series_top_rated",
    "Series · Top Rated · LOCAL",
    "discover/tv" +
        "?with_origin_country=IN" +
        "&sort_by=vote_average.desc" +
        "&vote_count.gte=20" +
        "&include_null_first_air_dates=false",
    "Series"
)
        )
    }

    private fun cinemetaDefinitions(): List<HomeDefinition> {
        return listOf(
            HomeDefinition("cin_top_movies", "Top Movies", "https://v3-cinemeta.strem.io/catalog/movie/top/skip=###", "Movies"),
            HomeDefinition("cin_top_tv_series", "Top TV Series", "https://v3-cinemeta.strem.io/catalog/series/top/skip=###", "Series"),
            HomeDefinition("cin_action_movies", "Action Movies", "https://v3-cinemeta.strem.io/catalog/movie/top/genre=Action&skip=###", "Movies"),
            HomeDefinition("cin_adventure_movies", "Adventure Movies", "https://v3-cinemeta.strem.io/catalog/movie/top/genre=Adventure&skip=###", "Movies"),
            HomeDefinition("cin_action_series", "Action Series", "https://v3-cinemeta.strem.io/catalog/series/top/genre=Action&skip=###", "Series"),
            HomeDefinition("cin_thriller_movies", "Thriller Movies", "https://v3-cinemeta.strem.io/catalog/movie/top/genre=Thriller&skip=###", "Movies"),
            HomeDefinition("cin_mystery_series", "Mystery Series", "https://v3-cinemeta.strem.io/catalog/series/top/genre=Mystery&skip=###", "Series"),
            HomeDefinition("cin_crime_movies", "Crime Movies", "https://v3-cinemeta.strem.io/catalog/movie/top/genre=Crime&skip=###", "Movies"),
            HomeDefinition("cin_scifi_movies", "Sci-Fi Movies", "https://v3-cinemeta.strem.io/catalog/movie/top/genre=Sci-Fi&skip=###", "Movies"),
            HomeDefinition("cin_fantasy_series", "Fantasy Series", "https://v3-cinemeta.strem.io/catalog/series/top/genre=Fantasy&skip=###", "Series"),
            HomeDefinition("cin_horror_movies", "Horror Movies", "https://v3-cinemeta.strem.io/catalog/movie/top/genre=Horror&skip=###", "Movies"),
            HomeDefinition("cin_comedy_series", "Comedy Series", "https://v3-cinemeta.strem.io/catalog/series/top/genre=Comedy&skip=###", "Series"),
            HomeDefinition("cin_romance_movies", "Romance Movies", "https://v3-cinemeta.strem.io/catalog/movie/top/genre=Romance&skip=###", "Movies"),
            HomeDefinition("cin_drama_series", "Drama Series", "https://v3-cinemeta.strem.io/catalog/series/top/genre=Drama&skip=###", "Series"),
            HomeDefinition("cin_animation_movies", "Animation Movies", "https://v3-cinemeta.strem.io/catalog/movie/top/genre=Animation&skip=###", "Movies"),
            HomeDefinition("cin_family_movies", "Family Movies", "https://v3-cinemeta.strem.io/catalog/movie/top/genre=Family&skip=###", "Movies"),
            HomeDefinition("cin_history_series", "History Series", "https://v3-cinemeta.strem.io/catalog/series/top/genre=History&skip=###", "Series"),
            HomeDefinition("cin_documentaries", "Documentaries", "https://v3-cinemeta.strem.io/catalog/movie/top/genre=Documentary&skip=###", "Movies")
        )
    }

    private fun selectedCatalogDefinitions(): List<HomeDefinition> {
        return when (Settings.getCatalogSource()) {
            Settings.CatalogSource.TMDB -> tmdbDefinitions()
            Settings.CatalogSource.CINEMETA -> cinemetaDefinitions()
            Settings.CatalogSource.STREMIO_ADDON -> emptyList()
        }
    }

    private fun definitionById(catalog: Settings.CatalogSource, id: String): HomeDefinition? {
        return when (catalog) {
            Settings.CatalogSource.TMDB -> tmdbDefinitions()
            Settings.CatalogSource.CINEMETA -> cinemetaDefinitions()
            Settings.CatalogSource.STREMIO_ADDON -> emptyList()
        }.firstOrNull { it.id == id }
    }

    // =========================================================
    // EFFECTIVE HOME CONFIG
    // =========================================================

    private fun effectiveHomeConfig(catalog: Settings.CatalogSource): Settings.HomeCatalogConfig {
        val defaults = Settings.defaultHomeCategories(catalog)
        val saved = Settings.getHomeCatalogConfig(catalog)
        val savedMap = saved.categories.associateBy { it.id }

        val categories = defaults.map { savedMap[it.id] ?: it }.toMutableList()

        saved.categories.filter { category ->
            category.custom && defaults.none { it.id == category.id }
        }.forEach { custom ->
            if (categories.none { it.id == custom.id }) {
                categories.add(custom)
            }
        }

        val order = mutableListOf<String>()
        saved.order.forEach { id ->
            if (categories.any { it.id == id } && id !in order) {
                order.add(id)
            }
        }

        categories.forEach { category ->
            if (category.id !in order) {
                order.add(category.id)
            }
        }

        return saved.copy(order = order, categories = categories)
    }

    private fun categoryForId(catalog: Settings.CatalogSource, id: String): Settings.HomeCategoryConfig? {
        return effectiveHomeConfig(catalog).categories.firstOrNull { it.id == id }
    }

    // =========================================================
    // MAIN PAGE
    // =========================================================

    override val mainPage: List<MainPageData>
        get() {
            val catalog = Settings.getCatalogSource()
            if (catalog == Settings.CatalogSource.STREMIO_ADDON) {
                val addon = Settings.getActiveCustomCatalog() ?: return emptyList()
                val baseUrl = addon.url.trim().takeIf { it.isNotBlank() } ?: return emptyList()

                var catalogs = addon.catalogs
                if (catalogs.isNullOrEmpty()) {
                    try {
                        catalogs = runBlocking {
                            app.get("$baseUrl/manifest.json").parsed<StremioManifest>()?.catalogs ?: emptyList()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                val categories = mutableListOf<Pair<String, String>>()

                catalogs.filter {
                    !it.id.isNullOrBlank() &&
                    !it.type.isNullOrBlank() &&
                    it.extra.none { extra -> extra.isRequired == true }
                }.forEach { catalogItem ->
                    val catalogId = catalogItem.id.orEmpty()
                    val catalogType = catalogItem.type.orEmpty()
                    val baseTitle = catalogItem.name?.trim()?.takeIf { it.isNotBlank() } ?: catalogId

                    val extraConfig = catalogItem.extra.firstOrNull { !it.name.isNullOrBlank() && !it.options.isNullOrEmpty() }

                    if (extraConfig != null && !extraConfig.options.isNullOrEmpty()) {
                        val extraName = extraConfig.name!!
                        extraConfig.options.forEach { option ->
                            val encodedOption = URLEncoder.encode(option, "UTF-8").replace("+", "%20")
                            val extraFilter = "$extraName=$encodedOption"
                            val rowTitle = "$baseTitle - $option"

                            val payload = StremioHomeRequest(
                                baseUrl = baseUrl,
                                type = catalogType,
                                id = catalogId,
                                title = rowTitle,
                                extraFilter = extraFilter
                            ).toJson()
                            categories.add("stremio:$payload" to rowTitle)
                        }
                    } else {
                        val payload = StremioHomeRequest(
                            baseUrl = baseUrl,
                            type = catalogType,
                            id = catalogId,
                            title = baseTitle
                        ).toJson()
                        categories.add("stremio:$payload" to baseTitle)
                    }
                }

                if (categories.isEmpty()) return emptyList()
                return mainPageOf(*categories.toTypedArray())
            }

            val defaults = selectedCatalogDefinitions()
            val config = effectiveHomeConfig(catalog)
            val byId = config.categories.associateBy { it.id }

            val result = config.order.mapNotNull { id ->
                val category = byId[id] ?: return@mapNotNull null
                if (!category.enabled) return@mapNotNull null
                val definition = defaults.firstOrNull { it.id == id }
                val data = if (definition != null) "home:$id" else "home:custom:$id"
                data to category.name
            }.toMutableList()

            // 🔥 Auto-inject any new HomeDefinitions (like Kitsu Anime categories) even if not in Settings.kt yet
            defaults.forEach { def ->
                if (byId[def.id] == null && result.none { it.first == "home:${def.id}" }) {
                    val insertIdx = result.size.coerceAtMost(3)
                    result.add(insertIdx, "home:${def.id}" to def.name)
                }
            }

            if (config.categoryShuffle) {
                result.shuffle()
            }

            return mainPageOf(*result.toTypedArray())
        }

    // =========================================================
    // GET HOME PAGE
    // =========================================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        runCatching { DesiExtensionPopup.show() }

        val catalog = Settings.getCatalogSource()
        val rawData = request.data

        if (rawData.startsWith("stremio:")) {
            return getStremioHomePage(page, request, rawData)
        }

        if (rawData.startsWith("home:")) {
            val id = rawData.removePrefix("home:").removePrefix("custom:")
            val category = categoryForId(catalog, id)
            val definition = definitionById(catalog, id)

            if (category != null && !category.enabled) {
                return newHomePageResponse(category.name, emptyList(), false)
            }

            if (category == null && definition != null) {
                val items = when {
                    isKitsuDefinition(definition) -> fetchKitsuDefinition(page, null, definition)
                    catalog == Settings.CatalogSource.TMDB -> fetchTmdbDefinition(page, null, definition)
                    else -> emptyList()
                }
                return newHomePageResponse(definition.name, items, items.isNotEmpty())
            }

            if (category == null) return newHomePageResponse(request.name, emptyList(), false)

            return when (catalog) {
                Settings.CatalogSource.TMDB -> getTmdbHomePage(page, request, category)
                Settings.CatalogSource.CINEMETA -> getCinemetaHomePage(page, request, category)
                Settings.CatalogSource.STREMIO_ADDON -> getStremioHomePage(page, request, rawData)
            }
        }

        return when (catalog) {
            Settings.CatalogSource.TMDB -> getTmdbLegacyPage(page, request)
            Settings.CatalogSource.CINEMETA -> getCinemetaLegacyPage(page, request)
            Settings.CatalogSource.STREMIO_ADDON -> getStremioHomePage(page, request, rawData)
        }
    }

    // =========================================================
    // KITSU ANIME HOME (Inside TMDB Catalog)
    // =========================================================

    private fun isKitsuDefinition(definition: HomeDefinition): Boolean {
        return definition.id.startsWith("kitsu_") ||
            definition.endpoint.startsWith(ApiConstants.KITSU_API) ||
            definition.endpoint.startsWith("kitsu_edge:")
    }

    private suspend fun fetchKitsuDefinition(
        page: Int,
        category: Settings.HomeCategoryConfig?,
        definition: HomeDefinition
    ): List<SearchResponse> {
        if (page == 1) {
            kitsuSkipMap[definition.id] = 0
            seenKitsuIds.removeIf { it.startsWith("${definition.id}:") }
        }

        val items = when (definition.id) {
            "kitsu_2026_anime" -> fetchKitsu2026Anime(page, definition.id)
            "kitsu_popular_anime" -> {
                val primary = fetchKitsuStremioCatalog(
                    page = page,
                    categoryId = definition.id,
                    endpointTemplate = "${ApiConstants.KITSU_API}/catalog/anime/kitsu-anime-popular/skip=###"
                )
                if (primary.isNotEmpty()) primary
                else {
                    val trending = fetchKitsuStremioCatalog(
                        page = page,
                        categoryId = definition.id,
                        endpointTemplate = "${ApiConstants.KITSU_API}/catalog/anime/kitsu-anime-trending/skip=###"
                    )
                    if (trending.isNotEmpty()) trending else fetchKitsuEdgeCatalog(page, definition.id, "sort=popularityRank")
                }
            }
            "kitsu_latest_episodes" -> {
                val airing = fetchKitsuStremioCatalog(
                    page = page,
                    categoryId = definition.id,
                    endpointTemplate = "${ApiConstants.KITSU_API}/catalog/anime/kitsu-anime-airing/skip=###"
                )
                if (airing.isNotEmpty()) airing
                else fetchKitsuEdgeCatalog(page, definition.id, "filter[status]=current&sort=-updatedAt")
            }
            else -> fetchKitsuStremioCatalog(page, definition.id, definition.endpoint)
        }

        return if (category != null) applyContentShuffle(category, items) else items
    }

    private suspend fun fetchKitsu2026Anime(page: Int, categoryId: String): List<SearchResponse> {
        // 1. Primary: Official Kitsu Edge API for 2026 seasonYear
        val edgeItems = fetchKitsuEdgeCatalog(
            page = page,
            categoryId = categoryId,
            queryParams = "filter[seasonYear]=2026&sort=-userCount"
        )
        if (edgeItems.isNotEmpty()) return edgeItems

        // 2. Fallback: Kitsu Stremio Addon filtered by 2026 releaseInfo
        val skip = (page - 1) * 20
        val urls = if (skip == 0) {
            listOf(
                "${ApiConstants.KITSU_API}/catalog/anime/kitsu-anime-airing.json",
                "${ApiConstants.KITSU_API}/catalog/anime/kitsu-anime-trending.json"
            )
        } else {
            listOf(
                "${ApiConstants.KITSU_API}/catalog/anime/kitsu-anime-airing/skip=$skip.json"
            )
        }

        val combined = mutableListOf<SearchResponse>()
        for (url in urls) {
            val res = runCatching { app.get(url).parsed<StremioCatalogResponse>() }.getOrNull() ?: continue
            res.metas.filter { it.releaseInfo?.contains("2026") == true }.forEach { meta ->
                mapKitsuStremioMetaToSearch(meta, categoryId)?.let { combined.add(it) }
            }
        }
        return combined
    }

    private suspend fun fetchKitsuStremioCatalog(
        page: Int,
        categoryId: String,
        endpointTemplate: String
    ): List<SearchResponse> {
        val skip = kitsuSkipMap[categoryId] ?: ((page - 1) * 20)
        val urlWithSkip = endpointTemplate.replace("###", skip.toString()) + ".json"
        val fallbackFirstPageUrl = endpointTemplate.replace("/skip=###", "") + ".json"

        val response = runCatching {
            val text = app.get(urlWithSkip).text
            tryParseJson<StremioCatalogResponse>(text)?.takeIf { it.metas.isNotEmpty() }
                ?: if (page == 1) tryParseJson<StremioCatalogResponse>(app.get(fallbackFirstPageUrl).text) else null
        }.getOrNull() ?: return emptyList()

        val items = response.metas.mapNotNull { meta ->
            mapKitsuStremioMetaToSearch(meta, categoryId)
        }

        kitsuSkipMap[categoryId] = skip + response.metas.size
        return items
    }

    private fun mapKitsuStremioMetaToSearch(meta: StremioCatalogMeta, categoryId: String): SearchResponse? {
        val rawId = meta.id?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val kitsuId = if (rawId.startsWith("kitsu:")) rawId else "kitsu:$rawId"
        val title = meta.aliases?.firstOrNull { it.isNotBlank() }
            ?: meta.name?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = meta.poster?.takeIf { it.isNotBlank() } ?: return null

        if (!seenKitsuIds.add("$categoryId:$kitsuId")) return null

        val imdbId = meta.imdbId ?: meta.imdb_id
        val tmdbId = meta.tmdbId?.toIntOrNull() ?: meta.tmdb_id?.toIntOrNull() ?: meta.moviedb_id?.toIntOrNull()
        val isMovie = meta.type.equals("movie", ignoreCase = true)

        val data = HybridData(
            tmdbId = tmdbId,
            cinemetaId = imdbId,
            mediaType = if (isMovie) "movie" else "anime",
            source = "stremio",
            title = title,
            poster = poster,
            background = meta.background,
            logo = meta.logo,
            plot = meta.description,
            year = extractYear(meta.releaseInfo),
            genres = meta.genres,
            imdbId = imdbId,
            episodes = null,
            stremioBaseUrl = ApiConstants.KITSU_API,
            stremioType = meta.type?.takeIf { it.isNotBlank() } ?: "anime",
            stremioId = kitsuId
        ).toJson()

        return newMovieSearchResponse(
            title,
            data,
            if (isMovie) TvType.AnimeMovie else TvType.Anime
        ) {
            posterUrl = poster
            score = (meta.imdbRating?.toDoubleOrNull() ?: meta.rating)?.takeIf { it > 0.0 }?.let { Score.from10(it) }
        }
    }

    private suspend fun fetchKitsuEdgeCatalog(
        page: Int,
        categoryId: String,
        queryParams: String
    ): List<SearchResponse> {
        val offset = (page - 1) * 20
        val url = "${ApiConstants.KITSU_EDGE_API}/anime?$queryParams&page[limit]=20&page[offset]=$offset"
        val response = runCatching {
            app.get(url, headers = mapOf("Accept" to "application/vnd.api+json")).parsed<KitsuEdgeResponse>()
        }.getOrNull() ?: return emptyList()

        return response.data.mapNotNull { item ->
            val rawId = item.id?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val kitsuId = "kitsu:$rawId"
            val attr = item.attributes ?: return@mapNotNull null

            val title = attr.titles?.en?.takeIf { it.isNotBlank() }
                ?: attr.canonicalTitle?.takeIf { it.isNotBlank() }
                ?: attr.titles?.en_jp?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val poster = attr.posterImage?.large
                ?: attr.posterImage?.original
                ?: attr.posterImage?.medium
                ?: return@mapNotNull null

            if (!seenKitsuIds.add("$categoryId:$kitsuId")) return@mapNotNull null

            val isMovie = attr.subtype.equals("movie", ignoreCase = true)
            val year = extractYear(attr.startDate)
            val rating10 = attr.averageRating?.toDoubleOrNull()?.let { it / 10.0 }

            val data = HybridData(
                tmdbId = null,
                cinemetaId = null,
                mediaType = if (isMovie) "movie" else "anime",
                source = "stremio",
                title = title,
                poster = poster,
                background = attr.coverImage?.original ?: attr.coverImage?.large,
                logo = null,
                plot = attr.synopsis,
                year = year,
                genres = null,
                imdbId = null,
                episodes = null,
                stremioBaseUrl = ApiConstants.KITSU_API,
                stremioType = "anime",
                stremioId = kitsuId
            ).toJson()

            newMovieSearchResponse(
                title,
                data,
                if (isMovie) TvType.AnimeMovie else TvType.Anime
            ) {
                posterUrl = poster
                score = rating10?.takeIf { it > 0.0 }?.let { Score.from10(it) }
            }
        }
    }

    // =========================================================
    // TMDB HOME
    // =========================================================

    private suspend fun getTmdbHomePage(page: Int, request: MainPageRequest, category: Settings.HomeCategoryConfig): HomePageResponse {
        val definition = definitionById(Settings.CatalogSource.TMDB, category.id)
        if (definition != null) {
            val items = if (isKitsuDefinition(definition)) {
                fetchKitsuDefinition(page, category, definition)
            } else {
                fetchTmdbDefinition(page, category, definition)
            }
            return newHomePageResponse(category.name, items, items.isNotEmpty())
        }
        val items = fetchTmdbCustomCategory(page, category)
        return newHomePageResponse(category.name, items, items.isNotEmpty())
    }

    private suspend fun fetchTmdbDefinition(page: Int, category: Settings.HomeCategoryConfig?, definition: HomeDefinition): List<SearchResponse> {
        val expectedProvider = when (definition.id) {
            "tmdb_netflix" -> 8
            "tmdb_prime_video" -> 119
            "tmdb_apple_tv_plus" -> 350
            "tmdb_disney_plus" -> 337
            "tmdb_hulu" -> 15
            "tmdb_jiohotstar" -> 122
            "tmdb_sonyliv" -> 237
            "tmdb_zee5" -> 232
            "tmdb_crunchyroll" -> 283
            else -> null
        }

        val region = when (definition.id) {
            "tmdb_jiohotstar", "tmdb_sonyliv", "tmdb_zee5", "tmdb_crunchyroll", "tmdb_netflix", "tmdb_prime_video" -> "IN"
            else -> "US"
        }

        var url = "${ApiConstants.TMDB_BASE}/${definition.endpoint}"
        val separator = if (url.contains("?")) "&" else "?"
        url += "${separator}api_key=${ApiConstants.TMDB_KEY}&page=$page"

        if (expectedProvider != null) {
            url += "&with_watch_providers=$expectedProvider&watch_region=$region"
        }

        when (definition.id) {
            "tmdb_mgm_plus" -> url += "&with_networks=621"
            "tmdb_paramount_plus" -> url += "&with_networks=4330"
            "tmdb_peacock" -> url += "&with_networks=3353"
        }

        val list = fetchTmdbList(url, definition.contentType)
        return if (category != null) applyContentShuffle(category, list) else list
    }

    private suspend fun fetchTmdbCustomCategory(page: Int, category: Settings.HomeCategoryConfig): List<SearchResponse> {
        return coroutineScope {
            val type = category.contentType.trim().lowercase()
            when (type) {
                "mixed" -> {
                    val movie = async { fetchTmdbCustomEndpoint(page, category, "movie") }
                    val series = async { fetchTmdbCustomEndpoint(page, category, "tv") }
                    val animeTv = async { fetchTmdbAnime(page, category, false) }
                    applyContentShuffle(category, dedupeSearchResults(movie.await() + series.await() + animeTv.await()))
                }
                "movies" -> applyContentShuffle(category, fetchTmdbCustomEndpoint(page, category, "movie"))
                "series" -> applyContentShuffle(category, fetchTmdbCustomEndpoint(page, category, "tv"))
                "anime" -> applyContentShuffle(category, fetchTmdbAnime(page, category, true))
                else -> emptyList()
            }
        }
    }

    private suspend fun fetchTmdbCustomEndpoint(page: Int, category: Settings.HomeCategoryConfig, media: String): List<SearchResponse> {
        val endpoint = if (media == "movie") "discover/movie" else "discover/tv"
        val params = mutableListOf<String>()

        when (category.sortBy.lowercase()) {
            "latest" -> params += if (media == "movie") "sort_by=primary_release_date.desc" else "sort_by=first_air_date.desc"
            "top rated" -> {
                params += "sort_by=vote_average.desc"
                params += "vote_count.gte=100"
            }
            else -> params += "sort_by=popularity.desc"
        }

        tmdbGenreId(category.genre)?.let { params += "with_genres=$it" }

        val url = "${ApiConstants.TMDB_BASE}/$endpoint?api_key=${ApiConstants.TMDB_KEY}&page=$page&" + params.joinToString("&")
        return fetchTmdbList(url, if (media == "movie") "Movies" else "Series")
    }

    private suspend fun fetchTmdbAnime(page: Int, category: Settings.HomeCategoryConfig, includeMovies: Boolean): List<SearchResponse> {
        val tvParams = mutableListOf("with_genres=16", "with_origin_country=JP")

        when (category.sortBy.lowercase()) {
            "latest" -> tvParams += "sort_by=first_air_date.desc"
            "top rated" -> {
                tvParams += "sort_by=vote_average.desc"
                tvParams += "vote_count.gte=100"
            }
            else -> tvParams += "sort_by=popularity.desc"
        }

        tmdbGenreId(category.genre)?.let { genreId -> tvParams[0] = "with_genres=16,$genreId" }

        val tvUrl = "${ApiConstants.TMDB_BASE}/discover/tv?api_key=${ApiConstants.TMDB_KEY}&page=$page&" + tvParams.joinToString("&")
        val tv = fetchTmdbList(tvUrl, "Anime")

        if (!includeMovies) return tv

        val movieParams = mutableListOf("with_keywords=210024|222243", "sort_by=popularity.desc")
        tmdbGenreId(category.genre)?.let { movieParams += "with_genres=16,$it" }

        val movieUrl = "${ApiConstants.TMDB_BASE}/discover/movie?api_key=${ApiConstants.TMDB_KEY}&page=$page&" + movieParams.joinToString("&")
        val movies = fetchTmdbList(movieUrl, "Anime")

        return dedupeSearchResults(movies + tv)
    }

    private suspend fun fetchTmdbList(url: String, contentType: String): List<SearchResponse> {
        val response = app.get(url).parsed<TmdbMultiSearchResponse>() ?: return emptyList()

        return response.results.mapNotNull { item ->
            val detectedType = item.media_type?.takeIf { it == "movie" || it == "tv" }
                ?: when {
                    url.contains("/movie") -> "movie"
                    url.contains("/tv") -> "tv"
                    !item.title.isNullOrBlank() || !item.release_date.isNullOrBlank() -> "movie"
                    else -> "tv"
                }

            val mediaType = when {
                contentType.equals("Movies", true) -> "movie"
                contentType.equals("Series", true) -> "tv"
                else -> detectedType
            }

            val title = item.title ?: item.name ?: return@mapNotNull null
            val tmdbId = item.id ?: return@mapNotNull null

            if (item.poster_path.isNullOrBlank()) return@mapNotNull null

            newMovieSearchResponse(
                title,
                HybridData(tmdbId = tmdbId, mediaType = mediaType, source = "tmdb").toJson(),
                if (mediaType == "movie") {
                    if (contentType.equals("Anime", true)) TvType.AnimeMovie else TvType.Movie
                } else {
                    if (contentType.equals("Anime", true)) TvType.Anime else TvType.TvSeries
                }
            ) {
                posterUrl = item.poster_path?.let { "${ApiConstants.TMDB_POSTER}$it" }
                score = Score.from10(item.vote_average)
            }
        }
    }

    // =========================================================
    // CINEMETA HOME
    // =========================================================

    private suspend fun getCinemetaHomePage(page: Int, request: MainPageRequest, category: Settings.HomeCategoryConfig): HomePageResponse {
        val definition = definitionById(Settings.CatalogSource.CINEMETA, category.id)
        if (definition != null) {
            val items = fetchCinemetaDefinition(page, category, definition)
            return newHomePageResponse(category.name, items, items.isNotEmpty())
        }
        val items = fetchCinemetaCustomCategory(page, category)
        return newHomePageResponse(category.name, items, items.isNotEmpty())
    }

    private suspend fun fetchCinemetaDefinition(page: Int, category: Settings.HomeCategoryConfig, definition: HomeDefinition): List<SearchResponse> {
        if (page == 1) {
            cinemetaSkipMap[category.id] = 0
            seenCinemetaIds.clear()
        }

        val skip = cinemetaSkipMap[category.id] ?: 0
        val endpoint = definition.endpoint.replace("###", skip.toString())
        val response = runCatching { app.get("$endpoint.json").text }.getOrNull() ?: return emptyList()
        val result = tryParseJson<HybridCineSearchResult>(response) ?: return emptyList()

        var items: List<SearchResponse> = result.metas.mapNotNull { item ->
            val id = item.id ?: return@mapNotNull null
            val title = item.aliases?.firstOrNull { it.isNotBlank() } ?: item.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val type = when (item.type.lowercase()) {
                "movie" -> TvType.Movie
                "series", "tv" -> TvType.TvSeries
                else -> return@mapNotNull null
            }

            if (item.poster.isNullOrBlank()) return@mapNotNull null
            if (!seenCinemetaIds.add("${category.id}:$id")) return@mapNotNull null

            newMovieSearchResponse(
                title,
                HybridData(cinemetaId = id, mediaType = item.type.lowercase(), source = "cinemeta").toJson(),
                type
            ) {
                posterUrl = item.poster
                score = item.imdbRating?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { Score.from10(it) }
            }
        }

        items = applyContentShuffle(category, items)
        cinemetaSkipMap[category.id] = skip + result.metas.size
        return items
    }

    private suspend fun fetchCinemetaCustomCategory(page: Int, category: Settings.HomeCategoryConfig): List<SearchResponse> {
        return coroutineScope {
            when (category.contentType.lowercase()) {
                "mixed" -> {
                    val movies = async { fetchCinemetaCustomEndpoint(page, category, "movie") }
                    val series = async { fetchCinemetaCustomEndpoint(page, category, "series") }
                    applyContentShuffle(category, dedupeSearchResults(movies.await() + series.await()))
                }
                "movies" -> applyContentShuffle(category, fetchCinemetaCustomEndpoint(page, category, "movie"))
                "series" -> applyContentShuffle(category, fetchCinemetaCustomEndpoint(page, category, "series"))
                "anime" -> applyContentShuffle(category, fetchCinemetaCustomEndpoint(page, category, "series", forceAnimation = true))
                else -> emptyList()
            }
        }
    }

    private suspend fun fetchCinemetaCustomEndpoint(page: Int, category: Settings.HomeCategoryConfig, type: String, forceAnimation: Boolean = false): List<SearchResponse> {
        val skipKey = "${category.id}:$type"
        if (page == 1) {
            cinemetaSkipMap[skipKey] = 0
        }

        val skip = cinemetaSkipMap[skipKey] ?: 0
        val genre = if (forceAnimation) "Animation" else category.genre?.takeIf { it.isNotBlank() }
        val genrePart = genre?.let { "&genre=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
        val endpoint = "https://v3-cinemeta.strem.io/catalog/$type/top/skip=$skip$genrePart"

        val response = runCatching { app.get("$endpoint.json").text }.getOrNull() ?: return emptyList()
        val result = tryParseJson<HybridCineSearchResult>(response) ?: return emptyList()

        val items = result.metas.mapNotNull { item ->
            val id = item.id ?: return@mapNotNull null
            val title = item.aliases?.firstOrNull { it.isNotBlank() } ?: item.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (item.poster.isNullOrBlank()) return@mapNotNull null

            val tvType = if (type == "movie") {
                if (forceAnimation) TvType.AnimeMovie else TvType.Movie
            } else {
                if (forceAnimation) TvType.Anime else TvType.TvSeries
            }

            newMovieSearchResponse(
                title,
                HybridData(cinemetaId = id, mediaType = type, source = "cinemeta").toJson(),
                tvType
            ) {
                posterUrl = item.poster
                score = item.imdbRating?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { Score.from10(it) }
            }
        }

        cinemetaSkipMap[skipKey] = skip + result.metas.size
        return items
    }

    // =========================================================
    // SHUFFLE
    // =========================================================

    private fun applyContentShuffle(category: Settings.HomeCategoryConfig, items: List<SearchResponse>): List<SearchResponse> {
        if (items.isEmpty()) return items
        return when (category.contentShuffle) {
            Settings.HomeShuffleMode.OFF -> items
            Settings.HomeShuffleMode.EVERY_TIME -> items.shuffled()
            Settings.HomeShuffleMode.DAILY -> {
                val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                val seed = ("$day:${category.id}").hashCode()
                items.shuffled(Random(seed))
            }
        }
    }

    private fun dedupeSearchResults(items: List<SearchResponse>): List<SearchResponse> {
        return items.distinctBy { it.url }
    }

    private fun tmdbGenreId(genre: String?): Int? {
        return when (genre?.lowercase()) {
            "action" -> 28
            "adventure" -> 12
            "animation" -> 16
            "comedy" -> 35
            "crime" -> 80
            "documentary" -> 99
            "drama" -> 18
            "family" -> 10751
            "fantasy" -> 14
            "history" -> 36
            "horror" -> 27
            "mystery" -> 9648
            "romance" -> 10749
            "sci-fi", "science fiction" -> 878
            "thriller" -> 53
            else -> null
        }
    }

    private suspend fun getTmdbLegacyPage(page: Int, request: MainPageRequest): HomePageResponse {
        val category = Settings.defaultHomeCategories(Settings.CatalogSource.TMDB)
            .firstOrNull { it.name == request.name } ?: return newHomePageResponse(request.name, emptyList())
        return getTmdbHomePage(page, request, category)
    }

    private suspend fun getCinemetaLegacyPage(page: Int, request: MainPageRequest): HomePageResponse {
        val category = Settings.defaultHomeCategories(Settings.CatalogSource.CINEMETA)
            .firstOrNull { it.name == request.name } ?: return newHomePageResponse(request.name, emptyList(), false)
        return getCinemetaHomePage(page, request, category)
    }

    // =========================================================
    // SEARCH
    // =========================================================

    override suspend fun search(query: String): List<SearchResponse> {
        return when (Settings.getCatalogSource()) {
            Settings.CatalogSource.TMDB -> searchTmdb(query)
            Settings.CatalogSource.CINEMETA -> searchCinemeta(query)
            Settings.CatalogSource.STREMIO_ADDON -> searchStremio(query)
        }
    }

    private suspend fun searchStremio(query: String): List<SearchResponse> = coroutineScope {
        val addon = Settings.getActiveCustomCatalog() ?: return@coroutineScope emptyList()
        val base = normalizeStremioBase(addon.url)
        val catalogs = addon.catalogs.filter { !it.id.isNullOrBlank() && !it.type.isNullOrBlank() }

        catalogs.map { catalog ->
            async {
                val all = fetchStremioCatalog(base, catalog.type.orEmpty(), catalog.id.orEmpty(), 1, null)
                all.filter { result -> result.name.contains(query, ignoreCase = true) }
            }
        }.awaitAll().flatten().distinctBy { it.url }
    }

    private suspend fun searchTmdb(query: String): List<SearchResponse> {
        val url = "${ApiConstants.TMDB_BASE}/search/multi?api_key=${ApiConstants.TMDB_KEY}&query=${URLEncoder.encode(query, "UTF-8")}"
        val response = app.get(url).parsed<TmdbMultiSearchResponse>() ?: return emptyList()

        return response.results.mapNotNull { item ->
            val mediaType = item.media_type?.takeIf { it == "movie" || it == "tv" } ?: return@mapNotNull null
            val title = item.title ?: item.name ?: return@mapNotNull null
            val tmdbId = item.id ?: return@mapNotNull null

            newMovieSearchResponse(
                title,
                HybridData(tmdbId = tmdbId, mediaType = mediaType, source = "tmdb").toJson(),
                if (mediaType == "movie") TvType.Movie else TvType.TvSeries
            ) {
                posterUrl = item.poster_path?.let { "${ApiConstants.TMDB_POSTER}$it" }
                score = Score.from10(item.vote_average)
            }
        }
    }

    private suspend fun searchCinemeta(query: String): List<SearchResponse> = coroutineScope {
        val sq = query.trim().takeIf { it.isNotEmpty() } ?: return@coroutineScope emptyList()
        val eq = URLEncoder.encode(sq, "UTF-8")
        val endpoints = listOf(
            "https://v3-cinemeta.strem.io/catalog/movie/top/search=$eq.json",
            "https://v3-cinemeta.strem.io/catalog/series/top/search=$eq.json"
        )

        endpoints.map { endpoint ->
            async<List<SearchResponse>> {
                val res = runCatching { app.get(endpoint).text }.getOrNull() ?: return@async emptyList<SearchResponse>()
                val result = tryParseJson<HybridCineSearchResult>(res) ?: return@async emptyList()

                result.metas.mapNotNull { item ->
                    val title = item.name?.takeIf { it.isNotBlank() } ?: item.aliases?.firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
                    val type = when (item.type.lowercase()) {
                        "movie" -> TvType.Movie
                        "series", "tv" -> TvType.TvSeries
                        else -> return@mapNotNull null
                    }
                    val id = item.id ?: return@mapNotNull null

                    newMovieSearchResponse(
                        name = title,
                        url = HybridData(cinemetaId = id, mediaType = item.type.lowercase(), source = "cinemeta").toJson(),
                        type = type
                    ) {
                        posterUrl = item.poster
                        item.imdbRating?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { score = Score.from10(it) }
                    }
                }
            }
        }.awaitAll().flatten().distinctBy { it.url }
    }

    // =========================================================
    // LOAD
    // =========================================================

    private val fastStartScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

override suspend fun load(url: String): LoadResponse? {
    val data = tryParseJson<HybridData>(url) ?: return null

    // ⚡ PRE-WARMING SYSTEM ⚡
    fastStartScope.launch {
        runCatching {
            val payload = AllLoadLinksData(
                tmdbId = data.tmdbId,
                imdbId = data.imdbId ?: data.cinemetaId,
                tvtype = data.mediaType,
                season = data.episodes?.firstOrNull()?.season, // Basic guess for TV
                episode = data.episodes?.firstOrNull()?.episode
            )
            val cacheKey = FastStartCache.key(payload.tmdbId, payload.imdbId, payload.season, payload.episode)
            
            if (FastStartCache.get(cacheKey) == null) {
                // Background me API race lagao
                ApiRacer.raceFastApis(payload, {}, { link ->
                    FastStartCache.put(cacheKey, link)
                })
            }
        }
    }
        return when (data.source) {
            "stremio" -> loadStremioCatalogItem(data, url)
            "tmdb" -> loadTmdb(data.tmdbId ?: return null, data.mediaType, url)
            "cinemeta" -> loadCinemeta(data.cinemetaId ?: return null, data.mediaType, url)
            else -> null
        }
    }

    // =========================================================
    // LOAD LINKS (Global Trigger)
    // =========================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return com.hindi.loadLinks(
            data = data,
            isCasting = isCasting,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    // =========================================================
    // STREMIO CATALOG
    // =========================================================

    private fun normalizeStremioBase(url: String): String {
        return url.trim().substringBefore('#').substringBefore('?').removeSuffix("/manifest.json").trimEnd('/')
    }

    private suspend fun fetchStremioManifest(baseUrl: String): StremioManifest? {
        val base = normalizeStremioBase(baseUrl)
        return runCatching { app.get("$base/manifest.json").parsed<StremioManifest>() }.getOrNull()
    }

    private fun stremioCatalogUrl(baseUrl: String, type: String, id: String, page: Int, extraFilter: String? = null): String {
        val base = normalizeStremioBase(baseUrl)
        val extraPath = if (!extraFilter.isNullOrBlank()) "/$extraFilter" else ""
        if (page <= 1) return "$base/catalog/$type/$id$extraPath.json"
        val skip = (page - 1) * 100
        return "$base/catalog/$type/$id$extraPath/skip=$skip.json"
    }

    private suspend fun fetchStremioCatalog(baseUrl: String, type: String, catalogId: String, page: Int, extraFilter: String? = null): List<SearchResponse> {
        val url = stremioCatalogUrl(baseUrl, type, catalogId, page, extraFilter)
        val result = runCatching { app.get(url).parsed<StremioCatalogResponse>() }.getOrNull() ?: return emptyList()

        return result.metas.orEmpty().mapNotNull { meta ->
            val title = meta.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val itemType = when (meta.type?.lowercase(Locale.ROOT)) {
                "movie" -> TvType.Movie
                "anime" -> TvType.Anime
                "series", "tv" -> TvType.TvSeries
                else -> TvType.TvSeries
            }

            val tmdbId = meta.tmdbId?.toIntOrNull() ?: meta.tmdb_id?.toIntOrNull() ?: meta.moviedb_id?.toIntOrNull()
            val imdbId = meta.imdbId ?: meta.imdb_id

            val stremioType = meta.type?.trim()?.takeIf { it.isNotBlank() } ?: type.trim()
            val mediaType = when (stremioType.lowercase(Locale.ROOT)) {
                "movie" -> "movie"
                "anime" -> "anime"
                else -> "series"
            }

            val data = HybridData(
                tmdbId = tmdbId, cinemetaId = imdbId, mediaType = mediaType, source = "stremio", title = title,
                poster = meta.poster, background = meta.background, logo = meta.logo, plot = meta.description,
                year = extractYear(meta.releaseInfo), genres = meta.genres, imdbId = imdbId, episodes = null,
                stremioBaseUrl = normalizeStremioBase(baseUrl), stremioType = stremioType, stremioId = meta.id
            ).toJson()

            newMovieSearchResponse(title, data, itemType) {
                posterUrl = meta.poster
                score = meta.rating?.toString()?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { Score.from10(it) }
            }
        }
    }

    private suspend fun getStremioHomePage(page: Int, request: MainPageRequest, rawData: String): HomePageResponse {
        val payload = rawData.removePrefix("stremio:")
        val home = tryParseJson<StremioHomeRequest>(payload) ?: return newHomePageResponse(request.name, emptyList(), false)

        val baseUrl = home.baseUrl?.takeIf { it.isNotBlank() } ?: Settings.getActiveStremioCatalogUrl() ?: return newHomePageResponse(request.name, emptyList(), false)
        val type = home.type?.trim()?.takeIf { it.isNotBlank() }
        val catalogId = home.id?.trim()?.takeIf { it.isNotBlank() }
        val extraFilter = home.extraFilter?.trim()?.takeIf { it.isNotBlank() }

        if (type != null && catalogId != null) {
            val items = fetchStremioCatalog(baseUrl, type, catalogId, page, extraFilter)
            return newHomePageResponse(home.title?.takeIf { it.isNotBlank() } ?: request.name, items, items.isNotEmpty())
        }

        val manifest = fetchStremioManifest(baseUrl) ?: return newHomePageResponse(request.name, emptyList(), false)
        val catalogs = manifest.catalogs.filter { !it.id.isNullOrBlank() && !it.type.isNullOrBlank() }.map { catalog ->
            catalog.copy(name = catalog.name?.trim()?.ifBlank { catalog.id })
        }

        val results = coroutineScope {
            catalogs.map { catalog ->
                async {
                    val catalogType = catalog.type.orEmpty()
                    val catalogIdValue = catalog.id.orEmpty()
                    fetchStremioCatalog(baseUrl, catalogType, catalogIdValue, page, null)
                }
            }.awaitAll().flatten()
        }

        return newHomePageResponse(request.name, results, results.isNotEmpty())
    }

    private suspend fun loadStremioCatalogItem(hybrid: HybridData, sourceUrl: String): LoadResponse? {
        val stremioBaseUrl = hybrid.stremioBaseUrl?.trim()?.takeIf { it.isNotBlank() }
        val stremioType = hybrid.stremioType?.trim()?.takeIf { it.isNotBlank() }
        val stremioId = hybrid.stremioId?.trim()?.takeIf { it.isNotBlank() }

        if (stremioBaseUrl != null && stremioType != null && stremioId != null) {
            val response = runCatching {
                loadStremioMetadata(stremioBaseUrl, stremioType, stremioId, sourceUrl, hybrid)
            }.getOrNull()

            if (response != null) return response
            return buildStremioHybridLoad(hybrid, sourceUrl)
        }

        hybrid.tmdbId?.let {
            val response = loadTmdb(it, if (hybrid.mediaType.equals("anime", true)) "tv" else hybrid.mediaType, sourceUrl)
            if (response != null) return response
        }

        hybrid.cinemetaId?.let {
            val mediaType = if (hybrid.mediaType.equals("movie", true)) "movie" else "series"
            val response = loadCinemeta(it, mediaType, sourceUrl)
            if (response != null) return response
        }

        return buildStremioHybridLoad(hybrid, sourceUrl)
    }

    private suspend fun loadStremioMetadata(baseUrl: String, type: String, id: String, sourceUrl: String, fallback: HybridData): LoadResponse? {
        val cleanBase = normalizeStremioBase(baseUrl)
        val cleanType = type.trim().trim('/')
        val cleanId = id.trim().trim('/')

        if (cleanBase.isBlank() || cleanType.isBlank() || cleanId.isBlank()) return null
        val encodedId = cleanId.replace(":", "%3A")

        val candidateUrls = listOf(
            "$cleanBase/meta/$cleanType/$encodedId.json",
            "$cleanBase/meta/anime/$encodedId.json",
            "$cleanBase/meta/series/$encodedId.json",
            "$cleanBase/meta/movie/$encodedId.json"
        ).distinct()

        var meta: StremioMeta? = null
        for (metaUrl in candidateUrls) {
            val parsedMeta = runCatching { app.get(metaUrl).parsed<StremioMetaResponse>()?.meta }.getOrNull()
            if (parsedMeta != null) {
                meta = parsedMeta
                break
            }
        }
        if (meta == null) return null

        val title = meta.aliases?.firstOrNull { it.isNotBlank() }
            ?: meta.name?.trim()?.takeIf { it.isNotBlank() }
            ?: fallback.title?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val normalizedType = meta.type?.trim()?.lowercase(Locale.ROOT) ?: cleanType.lowercase(Locale.ROOT)

        val resolvedId = meta.id ?: cleanId
        val kitsuIdStr = if (resolvedId.startsWith("kitsu:")) resolvedId.removePrefix("kitsu:") else null
        val externalIds = if (kitsuIdStr != null) runCatching { getExternalIds(kitsuIdStr, "kitsu") }.getOrNull() else null

        val isAnime = kitsuIdStr != null || cleanType.equals("anime", true) || normalizedType.equals("anime", true)

        val tvType = when {
            normalizedType == "movie" -> if (isAnime) TvType.AnimeMovie else TvType.Movie
            isAnime -> TvType.Anime
            else -> TvType.TvSeries
        }

        val tmdbId = meta.tmdbId?.toIntOrNull()
            ?: meta.tmdb_id?.toIntOrNull()
            ?: meta.moviedb_id?.toIntOrNull()
            ?: externalIds?.themoviedb
            ?: fallback.tmdbId

        val imdbId = meta.imdbId
            ?: meta.imdb_id
            ?: fallback.imdbId
            ?: fallback.cinemetaId
            ?: resolvedId.takeIf { it.startsWith("tt") }

        val year = extractYear(meta.releaseInfo ?: meta.year) ?: fallback.year
        val finalAniId = externalIds?.anilist
        val finalMalId = externalIds?.myanimelist
        val tvTypeString = if (tvType == TvType.Movie || tvType == TvType.AnimeMovie) "movie" else "tv"

        val linkData = LoadLinksData(
            title = title,
            id = imdbId ?: resolvedId,
            tmdbId = tmdbId,
            tvtype = tvTypeString,
            year = year?.toString(),
            isAnime = isAnime,
            isBollywood = false,
            isAsian = false,
            isCartoon = false,
            imdb_id = imdbId,
            anilistId = finalAniId,
            malId = finalMalId,
            kitsuId = kitsuIdStr,
            isKitsu = (kitsuIdStr != null),
            orgTitle = meta.name ?: title,
            airedYear = year
        )

        if (tvType == TvType.Movie || tvType == TvType.AnimeMovie) {
            return newMovieLoadResponse(
                name = title,
                url = sourceUrl,
                type = tvType,
                dataUrl = linkData.toJson()
            ) {
                posterUrl = meta.poster ?: fallback.poster
                backgroundPosterUrl = meta.background ?: fallback.background
                logoUrl = meta.logo ?: fallback.logo
                plot = meta.description ?: fallback.plot
                this.year = year
                tags = meta.genres ?: fallback.genres
                score = (meta.imdbRating?.toDoubleOrNull() ?: meta.rating)?.takeIf { it > 0.0 }?.let { Score.from10(it) }
                duration = extractRuntime(meta.runtime)
                addAniListId(finalAniId)
                addMalId(finalMalId)
            }.also { res -> addImdbIdToResponse(res, imdbId) }
        }

        val episodes = meta.videos.orEmpty().filter { (it.season ?: 1) != 0 }.mapNotNull { video ->
            val season = video.season ?: 1
            val episode = video.episode ?: return@mapNotNull null

            val episodeData = LoadLinksData(
                title = title,
                id = video.imdb_id ?: imdbId ?: resolvedId,
                tmdbId = tmdbId,
                tvtype = "tv",
                year = year?.toString(),
                season = season,
                episode = episode,
                firstAired = video.firstAired ?: video.released,
                isAnime = isAnime,
                isBollywood = false,
                isAsian = false,
                isCartoon = false,
                imdb_id = video.imdb_id ?: imdbId,
                imdbSeason = video.imdbSeason ?: season,
                imdbEpisode = video.imdbEpisode ?: episode,
                anilistId = finalAniId,
                malId = finalMalId,
                kitsuId = kitsuIdStr,
                isKitsu = (kitsuIdStr != null),
                orgTitle = meta.name ?: title,
                airedYear = extractYear(video.firstAired ?: video.released) ?: year
            )

            newEpisode(episodeData.toJson()) {
                name = video.title ?: video.name ?: "Episode $episode"
                this.season = season
                this.episode = episode
                description = video.overview
                posterUrl = video.thumbnail
                runTime = extractRuntime(video.runtime)
                (video.firstAired ?: video.released)?.let { addDate(it) }
                score = video.rating?.takeIf { it > 0.0 }?.let { Score.from10(it) }
            }
        }

        return newTvSeriesLoadResponse(
            name = title,
            url = sourceUrl,
            type = tvType,
            episodes = episodes
        ) {
            posterUrl = meta.poster ?: fallback.poster
            backgroundPosterUrl = meta.background ?: fallback.background
            logoUrl = meta.logo ?: fallback.logo
            plot = meta.description ?: fallback.plot
            this.year = year
            tags = meta.genres ?: fallback.genres
            score = (meta.imdbRating?.toDoubleOrNull() ?: meta.rating)?.takeIf { it > 0.0 }?.let { Score.from10(it) }
            addAniListId(finalAniId)
            addMalId(finalMalId)
        }.also { res -> addImdbIdToResponse(res, imdbId) }
    }

    private fun addImdbIdToResponse(response: LoadResponse, imdbId: String?) {
        if (!imdbId.isNullOrBlank()) {
            response.addImdbId(imdbId)
        }
    }

    private suspend fun buildStremioHybridLoad(data: HybridData, sourceUrl: String): LoadResponse? {
        val title = data.title?.takeIf { it.isNotBlank() } ?: data.cinemetaId ?: data.tmdbId?.toString() ?: return null
        val tvType = when (data.mediaType.lowercase(Locale.ROOT)) {
            "movie" -> TvType.Movie
            "anime" -> TvType.Anime
            else -> TvType.TvSeries
        }

        val resolvedId = data.stremioId ?: data.imdbId ?: data.cinemetaId ?: data.tmdbId?.toString() ?: ""
        val kitsuIdStr = if (resolvedId.startsWith("kitsu:")) resolvedId.removePrefix("kitsu:") else null
        val externalIds = if (kitsuIdStr != null) runCatching { getExternalIds(kitsuIdStr, "kitsu") }.getOrNull() else null
        val tvTypeString = if (tvType == TvType.Movie || tvType == TvType.AnimeMovie) "movie" else "tv"

        val linkData = LoadLinksData(
            title = title,
            id = resolvedId,
            tmdbId = data.tmdbId ?: externalIds?.themoviedb,
            tvtype = tvTypeString,
            year = data.year?.toString(),
            isAnime = tvType == TvType.Anime || kitsuIdStr != null,
            isBollywood = false,
            isAsian = false,
            isCartoon = false,
            imdb_id = data.imdbId ?: data.cinemetaId,
            anilistId = externalIds?.anilist,
            malId = externalIds?.myanimelist,
            kitsuId = kitsuIdStr,
            isKitsu = (kitsuIdStr != null),
            orgTitle = title,
            airedYear = data.year
        )

        if (tvType == TvType.Movie || tvType == TvType.AnimeMovie) {
            return newMovieLoadResponse(
                name = title,
                url = sourceUrl,
                type = if (tvType == TvType.AnimeMovie) TvType.AnimeMovie else TvType.Movie,
                dataUrl = linkData.toJson()
            ) {
                posterUrl = data.poster
                backgroundPosterUrl = data.background
                logoUrl = data.logo
                plot = data.plot
                year = data.year
                tags = data.genres
            }
        }

        val episodes = data.episodes.orEmpty().mapNotNull { ep ->
            val season = ep.season ?: return@mapNotNull null
            val episode = ep.episode ?: return@mapNotNull null

            val episodeData = LoadLinksData(
                title = title,
                id = resolvedId,
                tmdbId = data.tmdbId ?: externalIds?.themoviedb,
                tvtype = "tv",
                year = data.year?.toString(),
                season = season,
                episode = episode,
                firstAired = ep.released,
                isAnime = tvType == TvType.Anime || kitsuIdStr != null,
                isBollywood = false,
                isAsian = false,
                isCartoon = false,
                imdb_id = data.imdbId ?: data.cinemetaId,
                imdbSeason = season,
                imdbEpisode = episode,
                anilistId = externalIds?.anilist,
                malId = externalIds?.myanimelist,
                kitsuId = kitsuIdStr,
                isKitsu = (kitsuIdStr != null),
                orgTitle = title,
                airedYear = data.year
            )

            newEpisode(episodeData.toJson()) {
                name = ep.title ?: "Episode $episode"
                this.season = season
                this.episode = episode
                description = ep.overview
                posterUrl = ep.thumbnail
                runTime = ep.runtime
                ep.released?.let { addDate(it) }
            }
        }

        return newTvSeriesLoadResponse(
            name = title,
            url = sourceUrl,
            type = if (tvType == TvType.Anime || kitsuIdStr != null) TvType.Anime else TvType.TvSeries,
            episodes = episodes
        ) {
            posterUrl = data.poster
            backgroundPosterUrl = data.background
            logoUrl = data.logo
            plot = data.plot
            year = data.year
            tags = data.genres
        }
    }

    // =========================================================
    // TMDB LOAD
    // =========================================================

    private suspend fun loadTmdb(tmdbId: Int, mediaType: String, sourceUrl: String): LoadResponse? {
        val tmdb = app.get("${ApiConstants.TMDB_BASE}/$mediaType/$tmdbId?api_key=${ApiConstants.TMDB_KEY}&append_to_response=external_ids")
            .parsed<TmdbDetails>() ?: return null

        val metadata = MetadataAggregator.aggregate(
            imdbId = tmdb.external_ids?.imdbId,
            tmdbId = tmdbId,
            mediaType = mediaType,
            title = tmdb.title ?: tmdb.name
        )

        val country = metadata.countries.joinToString(" ") { it.name }
        val isCartoon = metadata.genres.any { it.contains("Animation", true) }
        val isAnime = metadata.anilistId != null
        val isBollywood = country.contains("India", true)
        val isAsian = (country.contains("Korea", true) || country.contains("China", true)) && !isAnime

        val linkData = LoadLinksData(
            title = metadata.title ?: "Unknown",
            id = metadata.imdbId ?: tmdbId.toString(),
            tmdbId = tmdbId,
            tvtype = mediaType,
            year = metadata.year?.toString(),
            isAnime = isAnime,
            isBollywood = isBollywood,
            isAsian = isAsian,
            isCartoon = isCartoon,
            imdb_id = metadata.imdbId,
            anilistId = metadata.anilistId,
            malId = metadata.malId,
            kitsuId = null,
            isKitsu = false,
            orgTitle = metadata.originalTitle,
            airedYear = metadata.year
        )

        return if (mediaType == "movie") {
            newMovieLoadResponse(
                metadata.title ?: "Unknown",
                sourceUrl,
                if (isAnime) TvType.AnimeMovie else TvType.Movie,
                linkData.toJson()
            ) {
                posterUrl = metadata.poster
                backgroundPosterUrl = metadata.backdrop
                logoUrl = metadata.logo
                plot = buildString {
                    metadata.countries.firstOrNull()?.let {
                        val flag = it.isoCode?.toFlagEmoji().orEmpty()
                        append("${"Origin".toSansSerifBold()}: $flag ${it.name.toSansSerifItalic()}\n\n")
                    }
                    metadata.awards?.let { append("${"🏆 Awards".toSansSerifBold()}: ${it.toSansSerifItalic()}\n\n") }
                    append("${"Description".toSansSerifBold()}: ${metadata.description?.toSansSerifItalic() ?: ""}")
                }
                tags = metadata.genres
                year = metadata.year
                contentRating = metadata.certification
                duration = metadata.runtime
                actors = metadata.cast.map { ActorData(Actor(it.name, it.image), roleString = it.role) }
                score = Score.from10(metadata.imdbRating ?: metadata.tmdbRating)
                addImdbId(metadata.imdbId)
                addAniListId(metadata.anilistId)
                addMalId(metadata.malId)
                metadata.trailer?.let { addTrailer(it) }
            }
        } else {
            val episodes = loadTmdbEpisodes(tmdbId, metadata, isAnime, isBollywood, isAsian, isCartoon)
            newAnimeLoadResponse(
                metadata.title ?: "Unknown",
                sourceUrl,
                if (isAnime) TvType.Anime else TvType.TvSeries
            ) {
                addEpisodes(DubStatus.Subbed, episodes)
                posterUrl = metadata.poster
                backgroundPosterUrl = metadata.backdrop
                logoUrl = metadata.logo
                plot = buildString {
                    metadata.countries.firstOrNull()?.let {
                        val flag = it.isoCode?.toFlagEmoji().orEmpty()
                        append("${"Origin".toSansSerifBold()}: $flag ${it.name.toSansSerifItalic()}\n\n")
                    }
                    metadata.awards?.let { append("${"🏆 Awards".toSansSerifBold()}: ${it.toSansSerifItalic()}\n\n") }
                    append("${"Description".toSansSerifBold()}: ${metadata.description?.toSansSerifItalic() ?: ""}")
                }
                tags = metadata.genres
                year = metadata.year
                contentRating = metadata.certification
                duration = metadata.runtime
                actors = metadata.cast.map { ActorData(Actor(it.name, it.image), roleString = it.role) }
                showStatus = when (metadata.status) {
                    "Returning Series", "In Production" -> ShowStatus.Ongoing
                    "Ended" -> ShowStatus.Completed
                    else -> null
                }
                score = Score.from10(metadata.imdbRating ?: metadata.tmdbRating)
                addImdbId(metadata.imdbId)
                addAniListId(metadata.anilistId)
                addMalId(metadata.malId)
                metadata.trailer?.let { addTrailer(it) }
            }
        }
    }

    private suspend fun loadTmdbEpisodes(
        tmdbId: Int,
        metadata: MetadataAggregator.AggregatedMetadata,
        isAnime: Boolean,
        isBollywood: Boolean,
        isAsian: Boolean,
        isCartoon: Boolean
    ): List<Episode> = coroutineScope {
        val series = app.get("${ApiConstants.TMDB_BASE}/tv/$tmdbId?api_key=${ApiConstants.TMDB_KEY}").parsed<TmdbDetails>() ?: return@coroutineScope emptyList()
        val seasonCount = series.number_of_seasons ?: return@coroutineScope emptyList()

        val seasonDeferred = (1..seasonCount).map { seasonNumber ->
            async {
                runCatching {
                    app.get("${ApiConstants.TMDB_BASE}/tv/$tmdbId/season/$seasonNumber?api_key=${ApiConstants.TMDB_KEY}").parsed<TmdbSeasonResponse>()
                }.getOrNull()
            }
        }

        val seasons = seasonDeferred.awaitAll().filterNotNull()
        val episodes = mutableListOf<Episode>()

        seasons.forEach { season ->
            season.episodes.forEach { episode ->

                val epData = LoadLinksData(
                    title = metadata.title ?: "Unknown",
                    id = metadata.imdbId ?: tmdbId.toString(),
                    tmdbId = tmdbId,
                    tvtype = "tv",
                    year = metadata.year?.toString(),
                    season = episode.season_number,
                    episode = episode.episode_number,
                    firstAired = episode.air_date,
                    isAnime = isAnime,
                    isBollywood = isBollywood,
                    isAsian = isAsian,
                    isCartoon = isCartoon,
                    imdb_id = metadata.imdbId,
                    imdbSeason = episode.season_number,
                    imdbEpisode = episode.episode_number,
                    anilistId = metadata.anilistId,
                    malId = metadata.malId,
                    kitsuId = null,
                    isKitsu = false,
                    orgTitle = metadata.originalTitle,
                    airedYear = extractYear(episode.air_date)
                )

                episodes.add(
                    newEpisode(epData.toJson()) {
                        this.season = episode.season_number
                        this.episode = episode.episode_number
                        this.name = episode.name
                        this.description = episode.overview
                        this.posterUrl = episode.still_path?.let { "${ApiConstants.TMDB_BACKDROP}$it" }
                        this.score = Score.from10(episode.vote_average)
                        this.runTime = episode.runtime
                        addDate(episode.air_date)
                    }
                )
            }
        }
        return@coroutineScope episodes
    }

    // =========================================================
    // CINEMETA LOAD
    // =========================================================

    private suspend fun loadCinemeta(cinemetaId: String, mediaType: String, sourceUrl: String): LoadResponse? {
        val response = runCatching { app.get("https://v3-cinemeta.strem.io/meta/$mediaType/$cinemetaId.json").text }.getOrNull() ?: return null
        val meta = tryParseJson<HybridCineMetaResponse>(response)?.meta ?: return null
        val title = meta.name?.takeIf { it.isNotBlank() } ?: return null

        val isAnime = detectAnime(meta)
        val animeIds = if (isAnime) resolveAnilist(title, extractYear(meta.year ?: meta.releaseInfo)) else null
        val isBollywood = detectBollywood(meta)
        val isAsian = detectAsian(meta, isAnime)
        val isCartoon = detectCartoon(meta, isAnime)

        val tvTypeStr = if (mediaType == "movie") "movie" else "tv"

        val linkData = LoadLinksData(
            title = title,
            id = meta.imdb_id ?: meta.imdbId ?: cinemetaId,
            tmdbId = meta.tmdbId ?: meta.moviedb_id,
            tvtype = tvTypeStr,
            year = extractYear(meta.year ?: meta.releaseInfo)?.toString(),
            isAnime = isAnime,
            isBollywood = isBollywood,
            isAsian = isAsian,
            isCartoon = isCartoon,
            imdb_id = meta.imdb_id ?: meta.imdbId,
            anilistId = animeIds?.first,
            malId = animeIds?.second,
            kitsuId = null,
            isKitsu = false,
            orgTitle = title,
            airedYear = extractYear(meta.year ?: meta.releaseInfo)
        )

        return if (mediaType == "movie") {
            newMovieLoadResponse(
                name = title,
                url = sourceUrl,
                type = if (isAnime) TvType.AnimeMovie else TvType.Movie,
                dataUrl = linkData.toJson()
            ) {
                posterUrl = meta.poster
                backgroundPosterUrl = meta.background
                logoUrl = meta.logo
                plot = buildString {
                    meta.awards?.takeIf { it.isNotBlank() }?.let { append("${"🏆 Awards".toSansSerifBold()}: ${it.toSansSerifItalic()}\n\n") }
                    append("${"Description".toSansSerifBold()}: ${meta.description?.toSansSerifItalic() ?: ""}")
                }
                tags = (meta.genres ?: meta.genre)?.filter { it.isNotBlank() }?.distinct()
                score = meta.imdbRating?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { Score.from10(it) }
                year = extractYear(meta.year ?: meta.releaseInfo)
                duration = extractRuntime(meta.runtime)
                val actorList = mutableListOf<ActorData>()
                meta.app_extras?.cast?.forEach { person ->
                    person.name?.takeIf { it.isNotBlank() }?.let { actorList.add(ActorData(Actor(it, person.photo), roleString = person.character)) }
                }
                if (actorList.isEmpty() && !meta.cast.isNullOrEmpty()) {
                    meta.cast.forEach { actName -> if (actName.isNotBlank()) actorList.add(ActorData(Actor(actName))) }
                }
                actors = actorList.takeIf { it.isNotEmpty() }
                contentRating = meta.certification
                addImdbId(meta.imdb_id ?: meta.imdbId)
                addAniListId(animeIds?.first)
                addMalId(animeIds?.second)
            }
        } else {
            val episodes = meta.videos.orEmpty().asSequence().filter { (it.season ?: 0) > 0 && (it.episode ?: 0) > 0 }.map { ep ->

                val epData = LoadLinksData(
                    title = title,
                    id = meta.imdb_id ?: meta.imdbId ?: cinemetaId,
                    tmdbId = meta.tmdbId ?: meta.moviedb_id,
                    tvtype = "tv",
                    year = extractYear(meta.year ?: meta.releaseInfo)?.toString(),
                    season = ep.season,
                    episode = ep.episode,
                    firstAired = ep.firstAired ?: ep.released,
                    isAnime = isAnime,
                    isBollywood = isBollywood,
                    isAsian = isAsian,
                    isCartoon = isCartoon,
                    imdb_id = meta.imdb_id ?: meta.imdbId,
                    imdbSeason = ep.season,
                    imdbEpisode = ep.episode,
                    anilistId = animeIds?.first,
                    malId = animeIds?.second,
                    kitsuId = null,
                    isKitsu = false,
                    orgTitle = title,
                    airedYear = extractYear(ep.firstAired ?: ep.released)
                )

                newEpisode(epData.toJson()) {
                    name = ep.name ?: ep.title
                    this.season = ep.season ?: 1
                    this.episode = ep.episode ?: 1
                    posterUrl = ep.thumbnail
                    description = ep.overview
                    runTime = extractRuntime(ep.runtime)
                    score = ep.rating?.toString()?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { Score.from10(it) }
                    addDate(ep.firstAired ?: ep.released)
                }
            }.toList()

            newTvSeriesLoadResponse(
                name = title,
                url = sourceUrl,
                type = if (isAnime) TvType.Anime else TvType.TvSeries,
                episodes = episodes
            ) {
                posterUrl = meta.poster
                backgroundPosterUrl = meta.background
                logoUrl = meta.logo
                plot = buildString {
                    meta.awards?.takeIf { it.isNotBlank() }?.let { append("${"🏆 Awards".toSansSerifBold()}: ${it.toSansSerifItalic()}\n\n") }
                    append("${"Description".toSansSerifBold()}: ${meta.description?.toSansSerifItalic() ?: ""}")
                }
                tags = (meta.genres ?: meta.genre)?.filter { it.isNotBlank() }?.distinct()
                score = meta.imdbRating?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { Score.from10(it) }
                year = extractYear(meta.year ?: meta.releaseInfo)
                duration = extractRuntime(meta.runtime)
                contentRating = meta.certification
                val actorList = mutableListOf<ActorData>()
                meta.app_extras?.cast?.forEach { person ->
                    person.name?.takeIf { it.isNotBlank() }?.let { actorList.add(ActorData(Actor(it, person.photo), roleString = person.character)) }
                }
                if (actorList.isEmpty() && !meta.cast.isNullOrEmpty()) {
                    meta.cast.forEach { actName -> if (actName.isNotBlank()) actorList.add(ActorData(Actor(actName))) }
                }
                actors = actorList.takeIf { it.isNotEmpty() }
                addImdbId(meta.imdb_id ?: meta.imdbId)
                addAniListId(animeIds?.first)
                addMalId(animeIds?.second)
            }
        }
    }

    // =========================================================
    // ANILIST
    // =========================================================

    private suspend fun resolveAnilist(title: String, year: Int?): Pair<Int?, Int?>? {
        val cleanTitle = title.trim().takeIf { it.isNotEmpty() } ?: return null
        val query = """
            query (${"$"}search: String) {
                Page(page: 1, perPage: 8) {
                    media(search: ${"$"}search, type: ANIME) {
                        id
                        idMal
                        seasonYear
                        title { romaji english native }
                    }
                }
            }
        """.trimIndent()

        val body = mapOf("query" to query, "variables" to mapOf("search" to cleanTitle)).toJson()
        val reqBody = body.toRequestBody("application/json".toMediaTypeOrNull())

        val response = runCatching {
            app.post("https://graphql.anilist.co", headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"), requestBody = reqBody).text
        }.getOrNull() ?: return null

        val result = tryParseJson<HybridAniListResponse>(response) ?: return null
        val media = result.data?.Page?.media?.maxByOrNull { calculateMatchScore(cleanTitle, it, year) } ?: return null

        if (calculateMatchScore(cleanTitle, media, year) < 50) return null
        return Pair(media.id, media.idMal)
    }

    private fun calculateMatchScore(searchTitle: String, anime: HybridAniListMedia, year: Int?): Int {
        val normalizedSearch = searchTitle.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        val titles = listOfNotNull(anime.title?.romaji, anime.title?.english, anime.title?.native)
        var score = 0

        for (t in titles) {
            val nt = t.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
            if (nt == normalizedSearch) {
                score = maxOf(score, 100)
            } else if (nt.contains(normalizedSearch) || normalizedSearch.contains(nt)) {
                score = maxOf(score, 70)
            }
        }
        if (year != null && anime.seasonYear == year) score += 25
        return score
    }

    // =========================================================
    // DETECTION
    // =========================================================

    private fun detectAnime(meta: HybridCineMeta): Boolean {
        val title = buildString {
            append(meta.name.orEmpty())
            append(" ")
            append(meta.aliases.orEmpty().joinToString(" "))
        }.lowercase()
        val country = meta.country.orEmpty().lowercase()
        val genres = meta.genres.orEmpty().joinToString(" ").lowercase()

        if (genres.contains("animation") && (country.contains("japan") || country.contains("china"))) return true
        return listOf("anime", "anime series", "japanese animation", "japanese anime").any { title.contains(it) || genres.contains(it) }
    }

    private fun detectCartoon(meta: HybridCineMeta, isAnime: Boolean): Boolean {
        if (isAnime) return false
        return meta.genres.orEmpty().any { it.contains("animation", true) } || meta.genre.orEmpty().any { it.contains("animation", true) }
    }

    private fun detectBollywood(meta: HybridCineMeta): Boolean = meta.country?.contains("India", true) == true

    private fun detectAsian(meta: HybridCineMeta, isAnime: Boolean): Boolean {
        if (isAnime) return false
        val country = meta.country.orEmpty()
        return country.contains("Korea", true) || country.contains("China", true) || country.contains("Japan", true)
    }

    private fun extractYear(value: String?): Int? = value?.substringBefore("-")?.substringBefore("–")?.trim()?.toIntOrNull()
    private fun extractRuntime(runtime: String?): Int? {
        if (runtime.isNullOrBlank()) return null
        return Regex("""\d+""").find(runtime)?.value?.toIntOrNull()
    }
}

// =============================================================
// HYBRID CINEMETA MODELS
// =============================================================

data class HybridCineSearchResult(val metas: List<HybridCineSearchItem> = emptyList())
data class HybridCineSearchItem(
    val id: String? = null,
    val type: String,
    val name: String? = null,
    val poster: String? = null,
    val imdbRating: String? = null,
    val aliases: List<String>? = null
)
data class HybridCineMetaResponse(val meta: HybridCineMeta? = null)
data class HybridCineMeta(
    val id: String? = null,
    val imdb_id: String? = null,
    val imdbId: String? = null,
    val awards: String? = null,
    val type: String? = null,
    val aliases: List<String>? = null,
    val certification: String? = null,
    val poster: String? = null,
    val logo: String? = null,
    val background: String? = null,
    val moviedb_id: Int? = null,
    val tmdbId: Int? = null,
    val name: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    val genres: List<String>? = null,
    val releaseInfo: String? = null,
    val status: String? = null,
    val runtime: String? = null,
    val cast: List<String>? = null,
    val app_extras: HybridCineAppExtras? = null,
    val language: String? = null,
    val country: String? = null,
    val imdbRating: String? = null,
    val year: String? = null,
    val videos: List<HybridCineEpisode>? = null
)
data class HybridCineAppExtras(val cast: List<HybridCineCast> = emptyList())
data class HybridCineCast(val name: String? = null, val character: String? = null, val photo: String? = null)
data class HybridCineEpisode(
    val id: String? = null,
    val name: String? = null,
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val overview: String? = null,
    val thumbnail: String? = null,
    val rating: String? = null,
    val released: String? = null,
    val firstAired: String? = null,
    val runtime: String? = null,
    val imdb_id: String? = null,
    val imdbSeason: Int? = null,
    val imdbEpisode: Int? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null
)
data class HybridAniListResponse(val data: HybridAniListData? = null)
data class HybridAniListData(val Page: HybridAniListPage? = null)
data class HybridAniListPage(val media: List<HybridAniListMedia> = emptyList())
data class HybridAniListMedia(val id: Int? = null, val idMal: Int? = null, val seasonYear: Int? = null, val title: HybridAniListTitle? = null)
data class HybridAniListTitle(val romaji: String? = null, val english: String? = null, val native: String? = null)

// =============================================================
// STREMIO & KITSU MODELS
// =============================================================

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioManifest(
    val id: String? = null,
    val name: String? = null,
    val catalogs: List<StremioManifestCatalog> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioManifestCatalog(
    val id: String? = null,
    val type: String? = null,
    val name: String? = null,
    val extra: List<StremioCatalogExtra> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioCatalogExtra(
    val name: String? = null,
    val isRequired: Boolean? = null,
    val options: List<String>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioCatalogResponse(
    val metas: List<StremioCatalogMeta> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioCatalogMeta(
    val id: String? = null,
    val type: String? = null,
    val name: String? = null,
    val aliases: List<String>? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val genres: List<String>? = null,
    val rating: Double? = null,
    val imdbRating: String? = null,
    @param:JsonProperty("imdb_id") val imdb_id: String? = null,
    @param:JsonProperty("imdbId") val imdbId: String? = null,
    @param:JsonProperty("tmdb_id") val tmdb_id: String? = null,
    @param:JsonProperty("tmdbId") val tmdbId: String? = null,
    @param:JsonProperty("moviedb_id") val moviedb_id: String? = null,
    val videos: List<StremioMetaVideo> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioMetaResponse(
    val meta: StremioMeta? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioMeta(
    val id: String? = null,
    val type: String? = null,
    val name: String? = null,
    val aliases: List<String>? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val year: String? = null,
    val genres: List<String>? = null,
    val rating: Double? = null,
    val imdbRating: String? = null,
    val runtime: String? = null,
    val country: String? = null,
    @param:JsonProperty("imdb_id") val imdb_id: String? = null,
    @param:JsonProperty("imdbId") val imdbId: String? = null,
    @param:JsonProperty("tmdb_id") val tmdb_id: String? = null,
    @param:JsonProperty("tmdbId") val tmdbId: String? = null,
    @param:JsonProperty("moviedb_id") val moviedb_id: String? = null,
    val videos: List<StremioMetaVideo> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioMetaVideo(
    val id: String? = null,
    val title: String? = null,
    val name: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val overview: String? = null,
    val thumbnail: String? = null,
    val released: String? = null,
    val firstAired: String? = null,
    val runtime: String? = null,
    val rating: Double? = null,
    @param:JsonProperty("imdb_id") val imdb_id: String? = null,
    val imdbSeason: Int? = null,
    val imdbEpisode: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioHomeRequest(
    val baseUrl: String? = null,
    val type: String? = null,
    val id: String? = null,
    val title: String? = null,
    val extraFilter: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KitsuEdgeResponse(
    val data: List<KitsuEdgeAnime> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KitsuEdgeAnime(
    val id: String? = null,
    val attributes: KitsuEdgeAttributes? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KitsuEdgeAttributes(
    val canonicalTitle: String? = null,
    val titles: KitsuEdgeTitles? = null,
    val synopsis: String? = null,
    val averageRating: String? = null,
    val startDate: String? = null,
    val subtype: String? = null,
    val posterImage: KitsuEdgeImage? = null,
    val coverImage: KitsuEdgeImage? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KitsuEdgeTitles(
    val en: String? = null,
    val en_jp: String? = null,
    val ja_jp: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KitsuEdgeImage(
    val medium: String? = null,
    val large: String? = null,
    val original: String? = null
)
