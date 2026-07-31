package com.vayunmathur.web.util

import android.net.Uri
import kotlinx.serialization.Serializable

@Serializable
data class BrowserTab(
    val id: String,
    val url: String = "",
    val title: String = "",
    val faviconUrl: String? = null,
    val isPrivate: Boolean = false,
)

val BrowserTab.isNewTab: Boolean
    get() = url.isBlank() || url == "about:blank"

/** Only DuckDuckGo is used now — choice removed per design request. */
enum class SearchEngine(
    val displayName: String,
    val searchUrl: String,
    val homepage: String,
) {
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s", "https://duckduckgo.com");

    fun buildQueryUrl(query: String): String =
        searchUrl.replace("%s", Uri.encode(query))

    companion object {
        val DEFAULT = DUCKDUCKGO
    }
}

enum class CacheMode(val title: String, val description: String, val webSettingsValue: Int) {
    DEFAULT("Default", "Use HTTP cache as needed", android.webkit.WebSettings.LOAD_DEFAULT),
    CACHE_ELSE_NETWORK("Cache first", "Prefer cache, fastest", android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK),
    NO_CACHE("No cache", "Always from network", android.webkit.WebSettings.LOAD_NO_CACHE),
    CACHE_ONLY("Offline", "Cache only, offline mode", android.webkit.WebSettings.LOAD_CACHE_ONLY);
}

enum class SitePermissionType(val key: String, val displayName: String) {
    CAMERA("camera", "Camera"),
    MICROPHONE("mic", "Microphone"),
    LOCATION("location", "Location"),
    NOTIFICATIONS("notifications", "Notifications")
}

object BrowserUtils {
    private val URL_LIKE = Regex(
        "^(https?://)?([a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}|localhost|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?(/.*)?$"
    )

    /** Kept as search fallback only — blank new tabs use "" not HOMEPAGE. */
    const val HOMEPAGE = "https://duckduckgo.com"

    /** Full address for display — no truncation. Keep prettyUrl() for subtitles. */
    fun displayFullUrl(url: String): String = url

    fun looksLikeUrl(text: String): Boolean {
        if (text.contains(" ")) return false
        val trimmed = text.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return true
        return URL_LIKE.matches(trimmed)
    }

    /** Search or navigate — always uses DuckDuckGo for queries. */
    fun toNavigationUrl(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return HOMEPAGE
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (looksLikeUrl(trimmed)) {
            return if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
        }
        return SearchEngine.DEFAULT.buildQueryUrl(trimmed)
    }

    // Legacy overload kept for call sites that still pass engine — always DuckDuckGo
    fun toNavigationUrl(input: String, @Suppress("UNUSED_PARAMETER") searchEngine: SearchEngine): String =
        toNavigationUrl(input)

    fun hostFromUrl(url: String): String {
        return try { Uri.parse(url).host ?: url } catch (_: Exception) { url }
    }

    fun originFromUrl(url: String): String {
        return try {
            val u = Uri.parse(url)
            val scheme = u.scheme ?: "https"
            val host = u.host ?: return url
            val port = if (u.port != -1) ":${u.port}" else ""
            "$scheme://$host$port"
        } catch (_: Exception) { url }
    }

    fun prettyUrl(url: String): String {
        if (url.isBlank()) return ""
        return try {
            val parsed = Uri.parse(url)
            val host = parsed.host ?: return url
            val path = parsed.path ?: ""
            val display = if (path.isEmpty() || path == "/") host else host + path
            if (display.length > 48) host else display
        } catch (_: Exception) { url }
    }
}
