package org.schabi.newpipe.extractor.downloader

import javax.annotation.Nonnull
import javax.annotation.Nullable

open class Response(
    private val responseCode: Int,
    private val responseMessage: String?,
    responseHeaders: Map<String, List<String>>?,
    responseBody: String?,
    private val latestUrl: String?
) {
    private val responseHeaders: Map<String, List<String>> =
        responseHeaders ?: emptyMap()

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
            if (key.equals(name, ignoreCase = true) && value.isNotEmpty()) {
                return value[0]
            }
        }
        return null
    }
}
