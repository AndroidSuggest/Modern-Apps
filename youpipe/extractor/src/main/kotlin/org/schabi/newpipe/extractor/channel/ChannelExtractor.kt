package org.schabi.newpipe.extractor.channel

import org.schabi.newpipe.extractor.Extractor
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler

abstract class ChannelExtractor(
    service: StreamingService,
    linkHandler: ListLinkHandler
) : Extractor(service, linkHandler) {

    companion object {
        const val UNKNOWN_SUBSCRIBER_COUNT: Long = -1
    }

    @Throws(ParsingException::class)
    abstract fun getAvatars(): List<Image>

    @Throws(ParsingException::class)
    abstract fun getBanners(): List<Image>

    @Throws(ParsingException::class)
    abstract fun getFeedUrl(): String?

    @Throws(ParsingException::class)
    abstract fun getSubscriberCount(): Long

    @Throws(ParsingException::class)
    abstract fun getDescription(): String?

    @Throws(ParsingException::class)
    abstract fun getParentChannelName(): String?

    @Throws(ParsingException::class)
    abstract fun getParentChannelUrl(): String?

    @Throws(ParsingException::class)
    abstract fun getParentChannelAvatars(): List<Image>

    @Throws(ParsingException::class)
    abstract fun isVerified(): Boolean

    @Throws(ParsingException::class)
    abstract fun getTabs(): List<ListLinkHandler>

    @Throws(ParsingException::class)
    open fun getTags(): List<String> = emptyList()
}
