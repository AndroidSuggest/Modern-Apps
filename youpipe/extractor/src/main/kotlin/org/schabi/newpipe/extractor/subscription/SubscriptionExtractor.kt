package org.schabi.newpipe.extractor.subscription

import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import java.io.IOException
import java.io.InputStream
import java.util.Collections

abstract class SubscriptionExtractor(
    protected val service: StreamingService,
    supportedSources: List<ContentSource>
) {

    class InvalidSourceException : ParsingException {
        constructor() : this(null, null)

        constructor(detailMessage: String?) : this(detailMessage, null)

        constructor(cause: Throwable?) : this(null, cause)

        constructor(detailMessage: String?, cause: Throwable?) : super(
            "Not a valid source" + if (detailMessage == null) "" else " ($detailMessage)",
            cause
        )
    }

    enum class ContentSource {
        CHANNEL_URL,
        INPUT_STREAM
    }

    private val supportedSources: List<ContentSource> =
        Collections.unmodifiableList(supportedSources)

    fun getSupportedSources(): List<ContentSource> = supportedSources

    @Throws(IOException::class, ExtractionException::class)
    abstract fun getRelatedUrl(): String?

    @Throws(IOException::class, ExtractionException::class)
    open fun fromChannelUrl(channelUrl: String): List<SubscriptionItem> {
        throw UnsupportedOperationException(
            "Service ${service.serviceInfo.name} doesn't support extracting from a channel url"
        )
    }

    @Throws(ExtractionException::class)
    open fun fromInputStream(contentInputStream: InputStream): List<SubscriptionItem> {
        throw UnsupportedOperationException(
            "Service ${service.serviceInfo.name} doesn't support extracting from an InputStream"
        )
    }

    @Throws(ExtractionException::class)
    open fun fromInputStream(
        contentInputStream: InputStream,
        contentType: String
    ): List<SubscriptionItem> {
        throw UnsupportedOperationException(
            "Service ${service.serviceInfo.name} doesn't support extracting from an InputStream"
        )
    }
}
