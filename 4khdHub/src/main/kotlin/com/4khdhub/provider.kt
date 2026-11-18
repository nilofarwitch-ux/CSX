package com.fourkhdhub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class ToonPlayProvider : MainAPI() {
    override var mainUrl = "https://toonplay.in"
    private val catalogApi = "https://animesalt.streamindia.co.in"
    private val extractApi = "https://anime.streamindia.co.in"
    private val animesaltUrl = "https://animesalt.cx"

    override var name = "ToonPlay"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Cartoon, TvType.AnimeMovie)

    // ============ 1. HOME (unchanged) ============
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = JSONObject(app.get("$catalogApi/api/home").text).getJSONObject("data")
        val sections = mutableListOf<HomePageList>()
        val sectionMap = listOf(
            "hero" to "Featured", "trending" to "Trending",
            "global_latest_releases" to "Latest Releases",
            "latest_releases" to "Recently Updated",
            "popular_series" to "Popular Series",
            "popular_movies" to "Popular Movies"
        )
        for ((key, label) in sectionMap) {
            val arr = data.optJSONArray(key) ?: continue
            val list = mutableListOf<SearchResponse>()
            for (i in 0 until arr.length()) list.add(parseItem(arr.getJSONObject(i)))
            if (list.isNotEmpty()) sections.add(HomePageList(label, list))
        }
        return newHomePageResponse(sections)
    }

    private fun parseItem(item: JSONObject): SearchResponse {
        val isMovie = item.optString("type", "series").lowercase() == "movie"
        return newAnimeSearchResponse(
            name = item.optString("title", "Unknown"),
            url = "$mainUrl/info/${item.optString("id")}",
            type = if (isMovie) TvType.AnimeMovie else TvType.Anime
        ) {
            this.posterUrl = item.optString("image",
                item.optString("poster", item.optString("image_url", "")))
        }
    }

    // ============ 2. SEARCH (unchanged) ============
    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val data = JSONObject(app.get("$catalogApi/api/search?q=$q&page=1").text)
            .optJSONArray("data") ?: return emptyList()
        val results = mutableListOf<SearchResponse>()
        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            if (item.optString("id", "").isBlank()) continue
            results.add(parseItem(item))
        }
        return results
    }

    // ============ 3. LOAD (unchanged) ============
    override suspend fun load(url: String): LoadResponse {
        val seriesId = url.substringAfterLast("/")
        val anime = JSONObject(app.get("$catalogApi/api/info?id=$seriesId").text)
            .getJSONObject("anime")
        val isMovie = anime.optString("type", "series").lowercase() == "movie"
        val genres = anime.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()

        val episodes = mutableListOf<Episode>()
        anime.optJSONArray("seasonsList")?.let { seasons ->
            for (s in 0 until seasons.length()) {
                val season = seasons.getJSONObject(s)
                val seasonNum = season.optString("season", "1").toIntOrNull()
                val eps = season.optJSONArray("episodes") ?: continue
                for (e in 0 until eps.length()) {
                    val ep = eps.getJSONObject(e)
                    val epId = ep.optString("id", "")
                    if (epId.isBlank() || epId == "null") continue
                    val epNum = ep.optInt("number", e + 1)
                    episodes.add(newEpisode("$mainUrl/watch/$epId") {
                        this.name = ep.optString("title", "Episode $epNum")
                        this.episode = epNum
                        this.season = seasonNum
                        this.posterUrl = ep.optString("image", "")
                    })
                }
            }
        }

        val status = anime.optString("status", "").lowercase()
        val isUpcoming = status.contains("not yet") || status.contains("upcoming") ||
                status == "tba" || status == "unreleased"

        return newAnimeLoadResponse(
            anime.optString("title", "Unknown"),
            url,
            if (isMovie) TvType.AnimeMovie else TvType.Anime
        ) {
            this.posterUrl = anime.optString("image", "")
            this.backgroundPosterUrl = anime.optString("backdrop", "")
            this.plot = anime.optString("overview", "")
            this.year = anime.optString("year", "").toIntOrNull() ?: 0
            this.tags = genres
            this.comingSoon = isUpcoming
            this.duration = anime.optString("episodeDuration", "24 min")
                .replace("[^0-9]".toRegex(), "").toIntOrNull()
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ============ 4. LOAD LINKS (updated with both extractors) ============
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epId = data.substringAfter("/watch/")
        val episodeUrl = "$animesaltUrl/$epId/"
        var found = false

        try {
            val encoded = URLEncoder.encode(episodeUrl, "UTF-8")
            val json = JSONObject(app.get("$extractApi/api/extract?url=$encoded").text)
            if (!json.optBoolean("success", false)) return false

            val dataObj = json.optJSONObject("data") ?: return false
            val realEpUrl = dataObj.optString("episodeUrl", episodeUrl)
            val servers = dataObj.optJSONArray("watchServers") ?: return false

            for (i in 0 until servers.length()) {
                val serverUrl = servers.getJSONObject(i).optString("url", "")
                if (serverUrl.isBlank()) continue

                when {
                    // --- Ravok direct ---
                    serverUrl.contains("ravok.buzz") -> {
                        if (extractRavok(serverUrl, realEpUrl, callback)) found = true
                    }
                    // --- Multi-lang plyr wrapper (base64 JSON) ---
                    serverUrl.contains("player.php?data=") -> {
                        val b64 = serverUrl.substringAfter("data=").substringBefore("&")
                        try {
                            val decoded = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
                            val arr = JSONArray(decoded)
                            for (j in 0 until arr.length()) {
                                val langObj = arr.getJSONObject(j)
                                val link = langObj.optString("link", "")
                                val lang = langObj.optString("language", "Unknown")
                                if (link.isBlank()) continue

                                if (link.contains("abyss")) {
                                    if (extractAbyss(link, lang, callback)) found = true
                                } else {
                                    try {
                                        if (loadExtractor(link, realEpUrl, subtitleCallback, callback))
                                            found = true
                                    } catch (_: Exception) {}
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    // --- Direct abyss (rare) ---
                    serverUrl.contains("abyss") -> {
                        if (extractAbyss(serverUrl, "Default", callback)) found = true
                    }
                    // --- Any other extractor ---
                    else -> {
                        try {
                            if (loadExtractor(serverUrl, realEpUrl, subtitleCallback, callback))
                                found = true
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return found
    }

    // ============ RAVOK EXTRACTOR ============
    private suspend fun extractRavok(
        ravokUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val videoId = ravokUrl.substringAfter("/video/").substringBefore("?").substringBefore("/")
            if (videoId.isBlank()) return false

            val apiUrl = "https://ravok.buzz/player/index.php?data=$videoId&do=getVideo"
            val headers = mapOf(
                "Origin" to "https://ravok.buzz",
                "Referer" to ravokUrl,
                "X-Requested-With" to "XMLHttpRequest"
            )
            val response = app.post(
                apiUrl,
                headers = headers,
                data = mapOf("hash" to videoId, "r" to referer)
            ).text

            val json = JSONObject(response)
            val streamUrl = json.optString("videoSource", "")
                .ifBlank { json.optString("securedLink", "") }
            if (streamUrl.isBlank()) return false

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "Ravok",
                    url = streamUrl,
                    type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                           else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://ravok.buzz/"
                }
            )
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ============ ABYSS EXTRACTOR (AES/CTR decrypt) ============
    private suspend fun extractAbyss(
        abyssUrl: String,
        language: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val abyssOrigin = Regex("^(https?://[^/]+)").find(abyssUrl)?.value
                ?: "https://abyssplayer.com"

            val html = app.get(abyssUrl, referer = abyssOrigin).text
            val datasB64 = Regex("const\\s+datas\\s*=\\s*\"([^\"]+)\"")
                .find(html)?.groupValues?.get(1) ?: return false

            val rawBytes = android.util.Base64.decode(datasB64, android.util.Base64.DEFAULT)
            val jsonStr = String(rawBytes, Charsets.ISO_8859_1)
            val jsonMap: Map<String, Any?> = tryParseJson(jsonStr) ?: return false

            val media = jsonMap["media"] as? String ?: return false
            val md5Id = (jsonMap["md5_id"] as? Number)?.toLong() ?: return false
            val userId = (jsonMap["user_id"] as? Number)?.toLong() ?: return false
            val slug = jsonMap["slug"] as? String ?: return false

            val cipherBytes = media.toByteArray(Charsets.ISO_8859_1)
            val passphrase = "$userId:$slug:$md5Id"
            val md5Hex = java.security.MessageDigest.getInstance("MD5")
                .digest(passphrase.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

            val keyBytes = md5Hex.toByteArray(Charsets.UTF_8)
            val iv = keyBytes.copyOfRange(0, 16)

            val cipher = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
                javax.crypto.spec.IvParameterSpec(iv)
            )
            val plainJson = String(cipher.doFinal(cipherBytes), Charsets.UTF_8)

            val mediaMap: Map<String, Any?> = tryParseJson(plainJson) ?: return false
            val mp4 = mediaMap["mp4"] as? Map<*, *> ?: return false
            val sources = mp4["sources"] as? List<*> ?: return false

            var found = false
            for (srcRaw in sources) {
                val src = srcRaw as? Map<*, *> ?: continue
                if (src["status"] == false) continue
                val base = src["url"] as? String ?: continue
                val path = src["path"] as? String ?: continue
                val label = src["label"] as? String

                val quality = when (label) {
                    "360p" -> Qualities.P360.value
                    "480p" -> Qualities.P480.value
                    "720p" -> Qualities.P720.value
                    "1080p" -> Qualities.P1080.value
                    "1440p" -> Qualities.P1440.value
                    "2160p" -> Qualities.P2160.value
                    else -> Qualities.Unknown.value
                }

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "Abyss ${label ?: ""} [$language]".trim(),
                        url = "$base/$path",
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = abyssOrigin
                        this.quality = quality
                    }
                )
                found = true
            }
            found
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}