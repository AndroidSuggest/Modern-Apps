package org.schabi.newpipe.extractor.subscription

import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import javax.annotation.Nonnull
import javax.annotation.Nullable

abstract class SubscriptionExtractor(
    protected val service: StreamingService,
    supportedSources: List<ContentSource>
) {

    /**
     * Exception that should be thrown when the input <b>do not</b> contain valid content that the
     * extractor can parse (e.g. nonexistent user in case of a url extraction).
     */
    class InvalidSourceException : ParsingException {

        constructor() : this(null, null)

        constructor(detailMessage: String?) : this(detailMessage, null)

        constructor(cause: Throwable?) : this(null, cause)

        constructor(@Nullable detailMessage: String?, @Nullable cause: Throwable?) : super(
            if (detailMessage == null) "Not a valid source" else "Not a valid source ($detailMessage)"
        ) {
            if (cause != null) {
                initCause(cause)
            }
        }
    }

    enum class ContentSource {
        CHANNEL_URL,
        INPUT_STREAM
    }

    private val supportedSources: List<ContentSource> =
        Collections.unmodifiableList(supportedSources)

    fun getSupportedSources(): List<ContentSource> = supportedSources

    @Nullable
    @Throws(IOException::class, ExtractionException::class)
    abstract fun getRelatedUrl(): String?

    @Throws(IOException::class, ExtractionException::class)
    open fun fromChannelUrl(channelUrl: String): List<SubscriptionItem> {
        throw UnsupportedOperationException(
            "Service ${service.serviceInfo.name} doesn't support extracting from a channel url"
        )
    }

    @Throws(ExtractionException::class)
    open fun fromInputStream(@Nonnull contentInputStream: InputStream): List<SubscriptionItem> {
        throw UnsupportedOperationException(
            "Service ${service.serviceInfo.name} doesn't support extracting from an InputStream"
        )
    }

    @Throws(ExtractionException::class)
    open fun fromInputStream(
        @Nonnull contentInputStream: InputStream,
        @Nonnull contentType: String
    ): List<SubscriptionItem> {
        throw UnsupportedOperationException(
            "Service ${service.serviceInfo.name} doesn't support extracting from an InputStream"
        )
    }
}
