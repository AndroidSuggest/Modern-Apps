package org.schabi.newpipe.extractor.linkhandler

import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor
import java.io.Serializable
import javax.annotation.Nonnull

class ReadyChannelTabListLinkHandler(
    url: String,
    channelId: String,
    @param:Nonnull channelTab: String,
    private val extractorBuilder: ChannelTabExtractorBuilder
) : ListLinkHandler(url, url, channelId, listOf(channelTab), "") {

    fun interface ChannelTabExtractorBuilder : Serializable {
        @Nonnull
        fun build(
            @Nonnull service: StreamingService,
            @Nonnull linkHandler: ListLinkHandler
        ): ChannelTabExtractor
    }

    @Nonnull
    fun getChannelTabExtractor(@Nonnull service: StreamingService): ChannelTabExtractor =
        extractorBuilder.build(service, ListLinkHandler(this))
}
