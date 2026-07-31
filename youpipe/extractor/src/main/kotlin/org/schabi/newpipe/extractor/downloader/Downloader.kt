package org.schabi.newpipe.extractor.downloader

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.HashMap
import javax.annotation.Nonnull
import javax.annotation.Nullable

abstract class Downloader {

    @Throws(IOException::class, ReCaptchaException::class)
    fun get(url: String): Response = get(url, null, NewPipe.getPreferredLocalization())

    @Throws(IOException::class, ReCaptchaException::class)
    fun get(url: String, localization: Localization): Response = get(url, null, localization)

    @Throws(IOException::class, ReCaptchaException::class)
    fun get(url: String, @Nullable headers: Map<String, List<String>>?): Response =
        get(url, headers, NewPipe.getPreferredLocalization())

    @Throws(IOException::class, ReCaptchaException::class)
    fun get(
        url: String,
        @Nullable headers: Map<String, List<String>>?,
        localization: Localization
    ): Response = execute(
        Request.newBuilder()
            .get(url)
            .headers(headers)
            .localization(localization)
            .build()
    )

    @Throws(IOException::class, ReCaptchaException::class)
    fun head(url: String): Response = head(url, null)

    @Throws(IOException::class, ReCaptchaException::class)
    fun head(url: String, @Nullable headers: Map<String, List<String>>?): Response =
        execute(
            Request.newBuilder()
                .head(url)
                .headers(headers)
                .build()
        )

    @Throws(IOException::class, ReCaptchaException::class)
    fun post(
        url: String,
        @Nullable headers: Map<String, List<String>>?,
        @Nullable dataToSend: ByteArray?
    ): Response = post(url, headers, dataToSend, NewPipe.getPreferredLocalization())

    @Throws(IOException::class, ReCaptchaException::class)
    fun post(
        url: String,
        @Nullable headers: Map<String, List<String>>?,
        @Nullable dataToSend: ByteArray?,
        localization: Localization
    ): Response = execute(
        Request.newBuilder()
            .post(url, dataToSend)
            .headers(headers)
            .localization(localization)
            .build()
    )

    @Throws(IOException::class, ReCaptchaException::class)
    fun postWithContentType(
        url: String,
        @Nullable headers: Map<String, List<String>>?,
        @Nullable dataToSend: ByteArray?,
        localization: Localization,
        contentType: String
    ): Response {
        val actualHeaders = HashMap<String, List<String>>()
        if (headers != null) {
            actualHeaders.putAll(headers)
        }
        actualHeaders["Content-Type"] = Collections.singletonList(contentType)
        return post(url, actualHeaders, dataToSend, localization)
    }

    @Throws(IOException::class, ReCaptchaException::class)
    fun postWithContentType(
        url: String,
        @Nullable headers: Map<String, List<String>>?,
        @Nullable dataToSend: ByteArray?,
        contentType: String
    ): Response = postWithContentType(
        url, headers, dataToSend, NewPipe.getPreferredLocalization(), contentType
    )

    @Throws(IOException::class, ReCaptchaException::class)
    fun postWithContentTypeJson(
        url: String,
        @Nullable headers: Map<String, List<String>>?,
        @Nullable dataToSend: ByteArray?,
        localization: Localization
    ): Response = postWithContentType(url, headers, dataToSend, localization, "application/json")

    @Throws(IOException::class, ReCaptchaException::class)
    fun postWithContentTypeJson(
        url: String,
        @Nullable headers: Map<String, List<String>>?,
        @Nullable dataToSend: ByteArray?
    ): Response = postWithContentTypeJson(
        url, headers, dataToSend, NewPipe.getPreferredLocalization()
    )

    @Throws(IOException::class, ReCaptchaException::class)
    abstract fun execute(@Nonnull request: Request): Response

    @Throws(IOException::class, ReCaptchaException::class)
    open fun getStreaming(
        url: String,
        @Nullable headers: Map<String, List<String>>?,
        @Nullable localization: Localization?
    ): StreamingResponse {
        val response = get(url, headers, localization ?: NewPipe.getPreferredLocalization())
        val raw = if (response.responseBody() == null) ByteArray(0)
        else response.responseBody().toByteArray(StandardCharsets.ISO_8859_1)
        return StreamingResponse(response.responseCode(), response.responseHeaders(), ByteArrayInputStream(raw))
    }

    @Throws(IOException::class, ReCaptchaException::class)
    open fun getStreaming(
        url: String,
        @Nullable headers: Map<String, List<String>>?,
        @Nullable localization: Localization?,
        timeoutMs: Long
    ): StreamingResponse = getStreaming(url, headers, localization)

    @Throws(IOException::class, ReCaptchaException::class)
    open fun postStreaming(
        url: String,
        @Nullable headers: Map<String, List<String>>?,
        @Nullable dataToSend: ByteArray?,
        @Nullable localization: Localization?
    ): StreamingResponse {
        val response = post(url, headers, dataToSend, localization ?: NewPipe.getPreferredLocalization())
        val raw = if (response.responseBody() == null) ByteArray(0)
        else response.responseBody().toByteArray(StandardCharsets.ISO_8859_1)
        return StreamingResponse(response.responseCode(), response.responseHeaders(), ByteArrayInputStream(raw))
    }

    override fun toString(): String = javaClass.simpleName
}
