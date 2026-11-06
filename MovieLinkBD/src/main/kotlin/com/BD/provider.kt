package com.lagradost.cloudstream3.movieproviders

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import org.jsoup.nodes.Element

class MovieLinkBD : MainAPI() {
    // Top ki JS script .at se .tv par redirect kar rahi hai, isliye direct .tv use karna behtar hai
    override var mainUrl = "https://movielinkbd.tv"
    override var name = "MovieLinkBD"
    override val hasMainPage = true
    override var lang = "bn"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Recently Updated",
        "$mainUrl/type/movies/page/" to "Movies",
        "$mainUrl/type/series/page/" to "Series & TV Shows",
        "$mainUrl/southIndian/page/" to "South Indian",
        "$mainUrl/drama/page/" to "K/J/C Drama",
        "$mainUrl/anime/page/" to "Anime Zone"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.movie-card").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?q=$query").document
        return document.select("div.movie-card").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("a.title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a.title")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img.mlbd-img")?.attr("data-src")
        
        val qualityStr = this.selectFirst("span.quality")?.text()?.trim()
        val quality = getQualityFromString(qualityStr)
        
        // Category check
        val isAnime = href.contains("/anime/")
        val isSeries = href.contains("/series/") || href.contains("/drama/") || this.selectFirst("span.type")?.text()?.contains("Series", true) == true
        
        return if (isSeries || isAnime) {
            val tvType = if (isAnime) TvType.Anime else TvType.TvSeries
            newTvSeriesSearchResponse(title, href, tvType) {
                this.posterUrl = fixUrlNull(posterUrl)
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrlNull(posterUrl)
                this.quality = quality
            }
        }
    }
}
