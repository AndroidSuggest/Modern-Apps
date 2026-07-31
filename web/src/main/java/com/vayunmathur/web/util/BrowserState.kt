package com.vayunmathur.web.util

import android.net.Uri
import kotlinx.serialization.Serializable

/**
 * Represents one open browser tab.
 * url is nullable and only non-null before the WebView loads; currentUrl tracks live navigations.
 */
@Serializable
data class BrowserTab(
    val id: String,
    val url: String = "",
    val title: String = "",
    val faviconUrl: String? = null,
    val isPrivate: Boolean = false,
)

/** Search engine configuration. */
@Serializable
enum class SearchEngine(
    val displayName: String,
    val searchUrl: String,
    val homepage: String,
) {
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s", "https://duckduckgo.com"),
    GOOGLE("Google", "https://www.google.com/search?q=%s", "https://www.google.com"),
    BRAVE("Brave Search", "https://search.brave.com/search?q=%s", "https://search.brave.com"),
    STARTPAGE("Startpage", "https://www.startpage.com/sp/search?query=%s", "https://www.startpage.com"),
    MOJEEK("Mojeek", "https://www.mojeek.com/search?q=%s", "https://www.mojeek.com");

    fun buildQueryUrl(query: String): String =
        searchUrl.replace("%s", Uri.encode(query))
}

object BrowserUtils {
    private val URL_LIKE = Regex(
        "^(https?://)?([a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}|localhost|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?(/.*)?$"
    )

    fun looksLikeUrl(text: String): Boolean {
        if (text.contains(" ")) return false
        val trimmed = text.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return true
        return URL_LIKE.matches(trimmed)
    }

    fun toNavigationUrl(input: String, searchEngine: SearchEngine): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return searchEngine.homepage
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (looksLikeUrl(trimmed)) {
            return if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
        }
        return searchEngine.buildQueryUrl(trimmed)
    }

    fun hostFromUrl(url: String): String {
        return try {
            Uri.parse(url).host ?: url
        } catch (_: Exception) {
            url
        }
    }

    fun prettyUrl(url: String): String {
        if (url.isBlank()) return ""
        return try {
            val parsed = Uri.parse(url)
            val host = parsed.host ?: return url
            val path = parsed.path ?: ""
            val display = if (path.isEmpty() || path == "/") host else host + path
            if (display.length > 48) host else display
        } catch (_: Exception) {
            url
        }
    }
}
