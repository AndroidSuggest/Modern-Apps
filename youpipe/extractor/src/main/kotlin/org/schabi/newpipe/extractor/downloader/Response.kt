package org.schabi.newpipe.extractor.downloader

import javax.annotation.Nonnull
import javax.annotation.Nullable
import java.util.Collections

open class Response(
    private val responseCode: Int,
    private val responseMessage: String?,
    responseHeaders: Map<String, List<String>>?,
    responseBody: String?,
    private val latestUrl: String?
) {
    private val responseHeaders: Map<String, List<String>> =
        responseHeaders ?: Collections.emptyMap()

    private val responseBody: String = responseBody ?: ""

    fun responseCode(): Int = responseCode
    fun responseMessage(): String? = responseMessage
    fun responseHeaders(): Map<String, List<String>> = responseHeaders

    @Nonnull
    fun responseBody(): String = responseBody

    @Nonnull
    fun latestUrl(): String = latestUrl ?: ""

    @Nullable
    fun getHeader(name: String): String? {
        for ((key, value) in responseHeaders) {
            if (key != null && key.equals(name, ignoreCase = true) && value.isNotEmpty()) {
                return value[0]
            }
        }
        return null
    }
}
