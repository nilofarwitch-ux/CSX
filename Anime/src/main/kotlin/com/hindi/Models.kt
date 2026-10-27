package com.hindi

import com.fasterxml.jackson.annotation.JsonProperty

// ═══════════════════ CONSTANTS ═══════════════════
object ApiConstants {
    const val CINEMETA_BASE  = "https://v3-cinemeta.strem.io"
    const val HAGLUND_BASE   = "https://arm.haglund.dev/api/v2"
    const val ANILIST_API    = "https://graphql.anilist.co"
    const val ANIZIP_API     = "https://api.ani.zip"
    const val JIKAN_API      = "https://api.jikan.moe/v4"
    const val KITSU_EDGE_API = "https://kitsu.io/api/edge"
    const val KITSU_API      = "https://anime-kitsu.strem.fun"
    const val TMDB_BASE      = "https://api.themoviedb.org/3"
    const val TMDB_IMAGE     = "https://image.tmdb.org/t/p"
    const val TMDB_POSTER    = "$TMDB_IMAGE/w780"
    const val TMDB_BACKDROP  = "$TMDB_IMAGE/original"
    const val TMDB_LOGO      = "$TMDB_IMAGE/w500"
    const val YOUTUBE_BASE   = "https://www.youtube.com/watch?v="
    const val TMDB_KEY       = "42ae27f7be70ca05f19e9b4d7d5d7ab2"
}

// ═══════════════════ INTERNAL ═══════════════════
data class AniData(
    val id: Int,
    val source: String = "anilist",   // "anilist" | "jikan" | "kitsu" | "tmdb"
    val format: String = "TV"
)

data class LoadLinksData(
    val title: String = "Unknown",
    val id: String = "",
    val tmdbId: Int? = null,
    val tvtype: String = "movie",
    val year: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val firstAired: String? = null,
    val isAnime: Boolean = false,
    val isBollywood: Boolean = false,
    val isAsian: Boolean = false,
    val isCartoon: Boolean = false,
    @JsonProperty("imdb_id") val imdb_id: String? = null,
    val anilistId: Int? = null,
    val malId: Int? = null,
    val kitsuId: String? = null,
    val orgTitle: String? = null,
    val airedYear: Int? = null
)

// ═══════════════════ ANILIST ═══════════════════
data class AniListResponse(@JsonProperty("data") val data: AniListData? = null)
data class AniListData(@JsonProperty("Media") val Media: AniListMedia? = null)

data class AniListMedia(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("idMal") val idMal: Int? = null,
    @JsonProperty("title") val title: AniListTitle? = null,
    @JsonProperty("coverImage") val coverImage: AniListCoverImage? = null,
    @JsonProperty("bannerImage") val bannerImage: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("averageScore") val averageScore: Int? = null,
    @JsonProperty("popularity") val popularity: Int? = null,
    @JsonProperty("season") val season: String? = null,
    @JsonProperty("seasonYear") val seasonYear: Int? = null,
    @JsonProperty("episodes") val episodes: Int? = null,
    @JsonProperty("genres") val genres: List<String> = emptyList(),
    @JsonProperty("studios") val studios: AniListStudios? = null,
    @JsonProperty("characters") val characters: AniListCharacters? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("duration") val duration: Int? = null,
    @JsonProperty("countryOfOrigin") val countryOfOrigin: String? = null,
    @JsonProperty("source") val source: String? = null,
    @JsonProperty("synonyms") val synonyms: List<String> = emptyList(),
    @JsonProperty("startDate") val startDate: AniListFuzzyDate? = null,
    @JsonProperty("endDate") val endDate: AniListFuzzyDate? = null,
    @JsonProperty("trailer") val trailer: AniListTrailer? = null,
    @JsonProperty("nextAiringEpisode") val nextAiringEpisode: AniListNextEpisode? = null
)

data class AniListTitle(val romaji: String?, val english: String?, val native: String?)
data class AniListCoverImage(val extraLarge: String?, val large: String?, val medium: String?)
data class AniListStudios(val nodes: List<AniListStudio> = emptyList())
data class AniListStudio(val name: String?)
data class AniListCharacters(val edges: List<AniListCharacterEdge> = emptyList())
data class AniListCharacterEdge(
    val role: String?,
    val node: AniListCharacterNode?,
    val voiceActors: List<AniListVoiceActor> = emptyList()
)
data class AniListCharacterNode(val name: AniListCharacterName?, val image: AniListVoiceActorImage?)
data class AniListCharacterName(val full: String?)
data class AniListVoiceActor(val name: AniListVoiceActorName?, val image: AniListVoiceActorImage?)
data class AniListVoiceActorName(val full: String?)
data class AniListVoiceActorImage(val large: String?)
data class AniListFuzzyDate(val year: Int?, val month: Int?, val day: Int?)
data class AniListTrailer(val id: String?, val site: String?)
data class AniListNextEpisode(val airingAt: Long?, val episode: Int?)

// ═══════════════════ TMDB ═══════════════════
data class TmdbDetails(
    val id: Int? = null,
    @JsonProperty("imdb_id") val imdb_id: String? = null,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val number_of_seasons: Int? = null,
    val vote_average: Double? = null,
    val vote_count: Int? = null,
    val runtime: Int? = null,
    val status: String? = null,
    val episode_run_time: List<Int>? = null,
    val genres: List<TmdbGenre>? = null,
    val production_countries: List<TmdbCountry>? = null,
    val credits: TmdbCredits? = null,
    val videos: TmdbVideos? = null,
    val images: TmdbImages? = null,
    val external_ids: TmdbExternalIds? = null,
    val content_ratings: TmdbContentRatings? = null,
    val release_dates: TmdbReleaseDates? = null
)

data class TmdbGenre(val id: Int?, val name: String?)
data class TmdbCountry(val iso_3166_1: String?, val name: String?)
data class TmdbCredits(val cast: List<TmdbCast> = emptyList())
data class TmdbCast(val id: Int?, val name: String?, val character: String?, val profile_path: String?)
data class TmdbVideos(val results: List<TmdbVideo> = emptyList())
data class TmdbVideo(val key: String?, val site: String?, val type: String?, val official: Boolean?)
data class TmdbImages(val logos: List<TmdbLogo> = emptyList())
data class TmdbLogo(val file_path: String?, val iso_639_1: String?, val vote_average: Double?, val vote_count: Int?)
data class TmdbExternalIds(@JsonProperty("imdb_id") val imdbId: String?)

data class TmdbSeasonResponse(val id: Int?, val season_number: Int?, val episodes: List<TmdbEpisode> = emptyList())
data class TmdbEpisode(
    val id: Int?, val name: String?, val overview: String?,
    val episode_number: Int?, val season_number: Int?, val air_date: String?,
    val still_path: String?, val vote_average: Double?, val runtime: Int?
)

data class TmdbContentRatings(val results: List<TmdbContentRating> = emptyList())
data class TmdbContentRating(val iso_3166_1: String?, val rating: String?)

data class TmdbReleaseDates(val results: List<TmdbReleaseCountry> = emptyList())
data class TmdbReleaseCountry(val iso_3166_1: String?, val release_dates: List<TmdbReleaseItem> = emptyList())
data class TmdbReleaseItem(val certification: String?)

data class TmdbMultiSearchResponse(val results: List<TmdbSearchResult> = emptyList())
data class TmdbSearchResult(
    val id: Int? = null,
    val name: String? = null,
    val title: String? = null,
    val originalName: String? = null,
    val originalTitle: String? = null,
    @JsonProperty("media_type") val media_type: String? = null,
    @JsonProperty("original_language") val originalLanguage: String? = null,
    @JsonProperty("genre_ids") val genreIds: List<Int> = emptyList(),
    val first_air_date: String? = null,
    val release_date: String? = null,
    val poster_path: String? = null
)

// ═══════════════════ CINEMETA ═══════════════════
data class CinemetaResponse(val meta: CinemetaMeta? = null)
data class CinemetaMeta(
    val name: String? = null,
    val description: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val year: String? = null,
    val genre: List<String>? = null,
    val genres: List<String>? = null,
    @JsonProperty("imdbRating") val imdbRating: String? = null,
    val awards: String? = null,
    val aliases: List<String>? = null
)

// ═══════════════════ HAGLUND ═══════════════════
data class ExternalIdsResponse(
    val anilist: Int? = null,
    val myanimelist: Int? = null,
    val kitsu: Int? = null,
    val anidb: Int? = null,
    val thetvdb: Int? = null
)

// ═══════════════════ JIKAN ═══════════════════
data class JikanAnime(
    @JsonProperty("mal_id") val malId: Int? = null,
    val title: String? = null,
    @JsonProperty("title_english") val titleEnglish: String? = null,
    val images: JikanImages? = null,
    val year: Int? = null,
    val type: String? = null,
    val score: Double? = null,
    val synopsis: String? = null,
    val episodes: Int? = null,
    val status: String? = null,
    val aired: JikanAired? = null,
    val genres: List<JikanNamedEntry>? = null,
    val members: Int? = null,
    val favorites: Int? = null,
    val rank: Int? = null,
    val duration: String? = null
)
data class JikanImages(val jpg: JikanImageUrls? = null, val webp: JikanImageUrls? = null)
data class JikanImageUrls(
    @JsonProperty("image_url") val imageUrl: String? = null,
    @JsonProperty("large_image_url") val largeImageUrl: String? = null
)
data class JikanAired(val from: String? = null, val to: String? = null, val string: String? = null)
data class JikanNamedEntry(val name: String? = null)
data class JikanEpisodeEntry(
    @JsonProperty("mal_id") val malId: Int? = null,
    val title: String? = null,
    val aired: String? = null,
    val filler: Boolean? = null,
    val recap: Boolean? = null
)

// ═══════════════════ KITSU ═══════════════════
data class KitsuResponse(val data: List<KitsuAnimeEntry> = emptyList())
data class KitsuAnimeEntry(val id: String? = null, val attributes: KitsuAnimeAttributes? = null)
data class KitsuAnimeAttributes(
    @JsonProperty("canonicalTitle") val canonicalTitle: String? = null,
    val titles: Map<String, String>? = null,
    @JsonProperty("abbreviatedTitles") val abbreviatedTitles: List<String>? = null,
    val synopsis: String? = null,
    @JsonProperty("posterImage") val posterImage: KitsuImage? = null,
    @JsonProperty("coverImage") val coverImage: KitsuImage? = null,
    val subtype: String? = null,
    val status: String? = null,
    @JsonProperty("startDate") val startDate: String? = null,
    @JsonProperty("endDate") val endDate: String? = null,
    @JsonProperty("averageRating") val averageRating: String? = null,
    @JsonProperty("userCount") val userCount: Int? = null,
    @JsonProperty("episodeCount") val episodeCount: Int? = null,
    @JsonProperty("episodeLength") val episodeLength: Int? = null,
    @JsonProperty("ageRating") val ageRating: String? = null,
    @JsonProperty("ageRatingGuide") val ageRatingGuide: String? = null
)
data class KitsuImage(
    val tiny: String? = null, val small: String? = null, val medium: String? = null,
    val large: String? = null, val original: String? = null
)

// ═══════════════════ ANIZIP ═══════════════════
data class AniZipResponse(
    @JsonProperty("episodes") val episodes: Map<String, AniZipEpisode> = emptyMap(),
    @JsonProperty("images") val images: List<AniZipImage> = emptyList(),
    @JsonProperty("mappings") val mappings: AniZipMappings? = null
)
data class AniZipEpisode(
    @JsonProperty("title") val title: Map<String, String>? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("image") val image: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("rating") val rating: String? = null,
    @JsonProperty("airDate") val airDate: String? = null
)
data class AniZipImage(
    @JsonProperty("coverType") val coverType: String? = null,
    @JsonProperty("url") val url: String? = null
)
data class AniZipMappings(
    @JsonProperty("anilist_id") val anilistId: Int? = null,
    @JsonProperty("mal_id") val malId: Int? = null,
    @JsonProperty("kitsu_id") val kitsuId: Int? = null,
    @JsonProperty("anidb_id") val anidbId: Int? = null,
    @JsonProperty("thetvdb_id") val thetvdbId: Int? = null,
    @JsonProperty("themoviedb_id") val themoviedbIds: List<Int>? = null
)
