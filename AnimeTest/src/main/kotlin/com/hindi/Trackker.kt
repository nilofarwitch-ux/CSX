package com.hindi

import java.util.Collections

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
