package org.schabi.newpipe.extractor.downloader

import org.schabi.newpipe.extractor.localization.Localization
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Objects
import javax.annotation.Nonnull
import javax.annotation.Nullable

class Request(
    @get:JvmName("httpMethod") val httpMethod: String,
    @get:JvmName("url") val url: String,
    headers: Map<String, List<String>>?,
    @get:JvmName("dataToSend") @param:Nullable val dataToSend: ByteArray?,
    @get:JvmName("localization") @param:Nullable val localization: Localization?,
    automaticLocalizationHeader: Boolean
) {
    @get:JvmName("headers")
    val headers: Map<String, List<String>>

    init {
        Objects.requireNonNull(httpMethod, "Request's httpMethod is null")
        Objects.requireNonNull(url, "Request's url is null")

        val actualHeaders = LinkedHashMap<String, List<String>>()
        if (headers != null) {
            actualHeaders.putAll(headers)
        }
        if (automaticLocalizationHeader && localization != null) {
            actualHeaders.putAll(getHeadersFromLocalization(localization))
        }
        this.headers = Collections.unmodifiableMap(actualHeaders)
    }

    private constructor(builder: Builder) : this(
        builder.httpMethod!!,
        builder.url!!,
        builder.headers,
        builder.dataToSend,
        builder.localization,
        builder.automaticLocalizationHeader
    )

    fun httpMethod(): String = httpMethod
    fun url(): String = url
    fun headers(): Map<String, List<String>> = headers
    fun dataToSend(): ByteArray? = dataToSend
    fun localization(): Localization? = localization

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val request = other as Request
        return httpMethod == request.httpMethod &&
            url == request.url &&
            headers == request.headers &&
            Arrays.equals(dataToSend, request.dataToSend) &&
            Objects.equals(localization, request.localization)
    }

    override fun hashCode(): Int {
        var result = Objects.hash(httpMethod, url, headers, localization)
        result = 31 * result + Arrays.hashCode(dataToSend)
        return result
    }

    class Builder {
        var httpMethod: String? = null
        var url: String? = null
        val headers: MutableMap<String, List<String>> = LinkedHashMap()
        var dataToSend: ByteArray? = null
        var localization: Localization? = null
        var automaticLocalizationHeader: Boolean = true

        fun httpMethod(httpMethodToSet: String): Builder {
            this.httpMethod = httpMethodToSet
            return this
        }

        fun url(urlToSet: String): Builder {
            this.url = urlToSet
            return this
        }

        fun headers(headersToSet: Map<String, List<String>>?): Builder {
            this.headers.clear()
            if (headersToSet != null) {
                this.headers.putAll(headersToSet)
            }
            return this
        }

        fun dataToSend(dataToSendToSet: ByteArray?): Builder {
            this.dataToSend = dataToSendToSet
            return this
        }

        fun localization(localizationToSet: Localization?): Builder {
            this.localization = localizationToSet
            return this
        }

        fun automaticLocalizationHeader(automaticLocalizationHeaderToSet: Boolean): Builder {
            this.automaticLocalizationHeader = automaticLocalizationHeaderToSet
            return this
        }

        fun build(): Request = Request(this)

        fun get(urlToSet: String): Builder {
            this.httpMethod = "GET"
            this.url = urlToSet
            return this
        }

        fun head(urlToSet: String): Builder {
            this.httpMethod = "HEAD"
            this.url = urlToSet
            return this
        }

        fun post(urlToSet: String, dataToSendToSet: ByteArray?): Builder {
            this.httpMethod = "POST"
            this.url = urlToSet
            this.dataToSend = dataToSendToSet
            return this
        }

        fun setHeaders(headerName: String, headerValueList: List<String>): Builder {
            this.headers.remove(headerName)
            this.headers[headerName] = headerValueList
            return this
        }

        fun addHeaders(headerName: String, headerValueList: List<String>): Builder {
            var currentHeaderValueList = this.headers[headerName]
            if (currentHeaderValueList == null) {
                currentHeaderValueList = ArrayList()
            }
            val mutable = currentHeaderValueList.toMutableList()
            mutable.addAll(headerValueList)
            this.headers[headerName] = headerValueList
            return this
        }

        fun setHeader(headerName: String, headerValue: String): Builder =
            setHeaders(headerName, Collections.singletonList(headerValue))

        fun addHeader(headerName: String, headerValue: String): Builder =
            addHeaders(headerName, Collections.singletonList(headerValue))
    }

    companion object {
        @JvmStatic
        fun newBuilder(): Builder = Builder()

        @JvmStatic
        @Nonnull
        fun getHeadersFromLocalization(@Nullable localization: Localization?): Map<String, List<String>> {
            if (localization == null) {
                return Collections.emptyMap()
            }
            val languageCode = localization.languageCode
            val languageCodeList = Collections.singletonList(
                if (localization.countryCode.isEmpty()) languageCode
                else localization.localizationCode + ", " + languageCode + ";q=0.9"
            )
            return Collections.singletonMap("Accept-Language", languageCodeList)
        }
    }
}
