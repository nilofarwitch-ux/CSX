@file:Suppress(
    "DEPRECATION",
    "DEPRECATION_ERROR",
    "UNUSED_PARAMETER",
    "UNCHECKED_CAST"
)

package OttSource

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.reflect.KClass
import kotlin.coroutines.resume

/**
 * Shared JSON parser
 */
val JSONParser = object : ResponseParser {

    private val mapper: ObjectMapper =
        jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true)

    override fun <T : Any> parse(text: String, kClass: KClass<T>): T {
        return mapper.readValue(text, kClass.java)
    }

    override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? {
        return try {
            mapper.readValue(text, kClass.java)
        } catch (_: Exception) {
            null
        }
    }

    override fun writeValueAsString(obj: Any): String {
        return mapper.writeValueAsString(obj)
    }
}

/**
 * Shared HTTP client
 */
val app = Requests(responseParser = JSONParser).apply {
    defaultHeaders = mapOf("User-Agent" to USER_AGENT)
}

inline fun <reified T : Any> parseJson(text: String): T {
    return JSONParser.parse(text, T::class)
}

inline fun <reified T : Any> tryParseJson(text: String): T? {
    return try {
        JSONParser.parseSafe(text, T::class)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

/**
 * Converts:
 * "2h 10m" -> 130
 * "45m"    -> 45
 * "1h"     -> 60
 */
fun convertRuntimeToMinutes(runtime: String): Int {
    var totalMinutes = 0
    val parts = runtime.trim().split(Regex("\\s+"))
    for (part in parts) {
        when {
            part.endsWith("h", ignoreCase = true) -> {
                val hours = part.removeSuffix("h").removeSuffix("H").trim().toIntOrNull() ?: 0
                totalMinutes += hours * 60
            }
            part.endsWith("m", ignoreCase = true) -> {
                val minutes = part.removeSuffix("m").removeSuffix("M").trim().toIntOrNull() ?: 0
                totalMinutes += minutes
            }
        }
    }
    return totalMinutes
}

// ============================================================
// BYPASS — with cf_clearance WebView solver
// ============================================================

/**
 * WebView se Cloudflare challenge solve karke cf_clearance nikalta hai.
 * 15s timeout ke saath.
 */
@SuppressLint("SetJavaScriptEnabled")
suspend fun solveCfClearance(targetUrl: String): String? =
    suspendCancellableCoroutine { cont ->

        val ctx = NetflixMirrorProvider.context ?: run {
            Log.e("NMDebug", "WEBVIEW ctx null — cannot solve cf")
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        Handler(Looper.getMainLooper()).post {
            try {
                val webView = WebView(ctx)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36"

                var resumed = false

                fun tryResume() {
                    if (resumed) return
                    val cookies = CookieManager.getInstance()
                        .getCookie("https://net52.cc")
                    val cf = cookies?.split(";")
                        ?.map { it.trim() }
                        ?.firstOrNull { it.startsWith("cf_clearance=") }
                        ?.substringAfter("cf_clearance=")

                    Log.d("NMDebug", "WEBVIEW cookies=${cookies?.take(200)}")
                    Log.d("NMDebug", "WEBVIEW cf_clearance=${cf?.take(60)}")

                    if (cf != null) {
                        resumed = true
                        cont.resume(cf)
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.d("NMDebug", "WEBVIEW pageFinished url=$url")
                        Handler(Looper.getMainLooper()).postDelayed({ tryResume() }, 3000L)
                    }
                }

                webView.loadUrl(targetUrl)

                Handler(Looper.getMainLooper()).postDelayed({
                    if (!resumed) {
                        Log.d("NMDebug", "WEBVIEW timeout — no cf_clearance")
                        resumed = true
                        cont.resume(null)
                    }
                }, 15_000L)

            } catch (e: Exception) {
                Log.e("NMDebug", "WEBVIEW error: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }
    }

suspend fun bypass(
    mainUrl: String,
    force: Boolean = false
): String {

    Log.d("NMDebug", "=== BYPASS START force=$force mainUrl=$mainUrl ===")

    if (force) {
        NetflixMirrorStorage.clearCookie()
    }

    val (savedCookie, savedTimestamp) = NetflixMirrorStorage.getCookie()
    Log.d("NMDebug", "BYPASS savedCookie=${savedCookie?.take(80)} age=${System.currentTimeMillis() - savedTimestamp}ms")

    val savedValid =
        !savedCookie.isNullOrEmpty() &&
        (savedCookie.contains("::") || savedCookie.contains("%3A%3A")) &&
        System.currentTimeMillis() - savedTimestamp < 43_200_000L

    Log.d("NMDebug", "BYPASS savedValid=$savedValid")

    if (savedValid) {
        Log.d("NMDebug", "BYPASS using cached cookie → return")
        return savedCookie!!
    }

    val defaultUa =
        "Mozilla/5.0 (Linux; Android 12; AC2001 Build/RKQ1.211119.001; wv) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/157.0.8103.14 " +
        "Mobile Safari/537.36 /OS.Gatu v3.1"

    val appReq = "app.netmirror.netmirrornew"
    val homeUrl = "$mainUrl/mobile/home?app=1"

    // -------- STEP 1: Home se addhash nikalo --------
    val homeRes = try {
        app.get(
            homeUrl,
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "User-Agent" to defaultUa,
                "X-Requested-With" to appReq
            )
        )
    } catch (e: Exception) {
        Log.e("NMDebug", "BYPASS home EXCEPTION: ${e.message}")
        return ""
    }

    Log.d("NMDebug", "BYPASS home status=${homeRes.okhttpResponse.code} len=${homeRes.text.length}")

    val addhash = Regex("""data-addhash="([^"]+)"""")
        .find(homeRes.text)?.groupValues?.getOrNull(1)?.trim()

    Log.d("NMDebug", "BYPASS addhash=${addhash?.take(120)}")

    if (addhash.isNullOrEmpty()) {
        Log.e("NMDebug", "BYPASS addhash NULL — home not loaded")
        Log.d("NMDebug", "BYPASS home preview: ${homeRes.text.take(400).replace("\n", " ")}")
        return ""
    }

    // -------- STEP 2: cf_clearance nikalo --------
    val existingCf = try {
        CookieManager.getInstance().getCookie("https://net52.cc")
            ?.split(";")
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("cf_clearance=") }
            ?.substringAfter("cf_clearance=")
    } catch (_: Exception) { null }

    Log.d("NMDebug", "BYPASS existing cf_clearance=${existingCf?.take(60)}")

    val userverUrl = "https://userver.net52.cc/?ffr455=$addhash&a=y&t=${Math.random()}"

    val cfClearance = existingCf ?: run {
        Log.d("NMDebug", "BYPASS cf missing → WebView solve")
        solveCfClearance(userverUrl)
    }

    Log.d("NMDebug", "BYPASS final cf_clearance=${cfClearance?.take(60)}")

    // -------- STEP 3: userver hit with cf_clearance --------
    val userverUa =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36"

    val userverCookies = buildMap {
        if (cfClearance != null) put("cf_clearance", cfClearance)
    }

    try {
        val userRes = app.get(
            userverUrl,
            headers = mapOf(
                "User-Agent" to userverUa,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Accept-Language" to "en-US,en;q=0.9,hi;q=0.8",
                "sec-ch-ua" to "\"Chromium\";v=\"154\", \"Google Chrome\";v=\"154\", \"Not A(Brand\";v=\"99\"",
                "sec-ch-ua-mobile" to "?1",
                "sec-ch-ua-platform" to "\"Android\"",
                "sec-ch-ua-model" to "\"AC2001\"",
                "sec-ch-ua-platform-version" to "\"12.0.0\"",
                "sec-fetch-site" to "cross-site",
                "sec-fetch-mode" to "navigate",
                "sec-fetch-dest" to "document",
                "sec-fetch-user" to "?1",
                "Upgrade-Insecure-Requests" to "1",
                "Referer" to homeUrl
            ),
            cookies = userverCookies,
            timeout = 10L
        )
        Log.d("NMDebug", "BYPASS userver status=${userRes.okhttpResponse.code} location=${userRes.okhttpResponse.headers("Location")}")
    } catch (e: Exception) {
        Log.d("NMDebug", "BYPASS userver EXCEPTION: ${e.message}")
    }

    // -------- STEP 4: verify2.php poll --------
    for (i in 1..10) {

        delay(if (i == 1) 4000L else 5000L)

        val verifyRes = try {
            app.post(
                "$mainUrl/mobile/verify2.php",
                headers = mapOf(
                    "Accept" to "*/*",
                    "User-Agent" to defaultUa,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to homeUrl,
                    "Origin" to mainUrl,
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                data = mapOf("verify" to addhash)
            )
        } catch (e: Exception) {
            Log.d("NMDebug", "BYPASS poll[$i] EXCEPTION: ${e.message}")
            continue
        }

        val body = verifyRes.text
        val setCookies = verifyRes.okhttpResponse.headers("Set-Cookie")
        Log.d("NMDebug", "BYPASS poll[$i] status=${verifyRes.okhttpResponse.code} body=${body.take(100)}")
        Log.d("NMDebug", "BYPASS poll[$i] Set-Cookie=$setCookies")

        val done =
            body.contains("\"statusup\":\"All Done\"") ||
            body.contains("All Done", ignoreCase = true)

        if (!done) continue

        Log.d("NMDebug", "BYPASS poll[$i] ✅ ALL DONE")

        val cookieHeader = verifyRes.okhttpResponse
            .headers("Set-Cookie")
            .firstOrNull { it.contains("t_hash_t=", ignoreCase = true) }

        if (cookieHeader != null) {
            val cookie = cookieHeader
                .substringAfter("t_hash_t=", "")
                .substringBefore(";")
                .trim()

            Log.d("NMDebug", "BYPASS extracted t_hash_t len=${cookie.length} val=${cookie.take(120)}")

            if (cookie.isNotEmpty()) {
                NetflixMirrorStorage.saveCookie(cookie)
                return cookie
            }
        }
    }

    Log.e("NMDebug", "BYPASS FAILED — 10 polls done, no All Done")
    return ""
}

// ============================================================
// NewTV / check.php helpers (kept for future use)
// ============================================================

val newTvBaseHeaders = mapOf(
    "Cache-Control" to "no-cache, no-store, must-revalidate",
    "Pragma" to "no-cache",
    "Expires" to "0",
    "X-Requested-With" to "NetmirrorNewTV v1.0",
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.GatuNewTV v1.0",
    "Accept" to "application/json, text/plain, */*"
)

val newTvDomains = listOf(
    "aHR0cHM6Ly9tb2JpbGVkZXRlY3RzLmNvbQ==",
    "aHR0cHM6Ly9tb2JpbGVkZXRlY3QuYXBw",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmFydA==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmNj",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmNsaWNr",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0Lmluaw==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmxpdmU=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnBybw==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNob3A=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNpdGU=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNwYWNl",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnN0b3Jl",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnZpcA==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0Lndpa2k=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0Lnh5eg==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5hcnQ=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5jYw==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5pbmZv",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5pbms=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5saXZl",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5wcm8=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5zdG9yZQ==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy50b3A=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy54eXo="
)

fun decodeBase64(value: String): String {
    return String(Base64.decode(value, Base64.DEFAULT))
}

private var resolvedApiUrl: String = ""

suspend fun resolveApiUrl(): String {
    if (resolvedApiUrl.isNotBlank()) return resolvedApiUrl
    for (encoded in newTvDomains) {
        val base = decodeBase64(encoded).trimEnd('/')
        try {
            val response = app.get("$base/checknewtv.php", headers = newTvBaseHeaders)
                .parsed<NewTvTokenResponse>()
            val tokenHash = response.token_hash
            if (!tokenHash.isNullOrBlank()) {
                resolvedApiUrl = decodeBase64(tokenHash).trimEnd('/')
                return resolvedApiUrl
            }
        } catch (_: Exception) { }
    }
    throw Exception("Failed to resolve NewTV API base URL")
}

fun buildNewTvHeaders(ott: String, extra: Map<String, String> = emptyMap()): Map<String, String> {
    val result = newTvBaseHeaders.toMutableMap()
    result["Ott"] = ott
    extra.forEach { (key, value) -> result[key] = value }
    return result
}

data class NewTvTokenResponse(
    val token_hash: String? = null,
    val doms: String? = null,
    val mwin: String? = null,
    val popwin: String? = null,
    val `var`: String? = null,
    val stape: String? = null,
    val u: List<String>? = null
)

data class NewTvPlayerResponse(
    val status: String? = null,
    val video_link: String? = null,
    val referer: String? = null
)
