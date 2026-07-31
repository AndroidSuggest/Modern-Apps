package com.vayunmathur.library.network

import java.net.URI
import java.net.URLEncoder

/**
 * Minimal URL helpers – enough to build query strings and inspect hosts
 * without pulling in a URL-builder dependency.
 */
object Urls {

    /** Host of [url], or "" if it can't be parsed. */
    fun host(url: String): String = runCatching { URI(url).host }.getOrNull().orEmpty()

    /**
     * Append [params] as query parameters, form-encoding keys and values.
     * The existing query string is left byte-for-byte intact — callers that
     * hand-encode parts of a URL keep their encoding.
     */
    fun appendQuery(url: String, params: Map<String, String>): String {
        if (params.isEmpty()) return url
        val sb = StringBuilder(url)
        // Whether a separator is still owed before the next parameter.
        var needSeparator = url.endsWith('?') || url.endsWith('&')
        if (!needSeparator) sb.append(if (url.contains('?')) '&' else '?')
        needSeparator = false
        for ((k, v) in params) {
            if (needSeparator) sb.append('&')
            needSeparator = true
            sb.append(encode(k)).append('=').append(encode(v))
        }
        return sb.toString()
    }

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
}
