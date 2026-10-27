package com.hindi

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.app

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
