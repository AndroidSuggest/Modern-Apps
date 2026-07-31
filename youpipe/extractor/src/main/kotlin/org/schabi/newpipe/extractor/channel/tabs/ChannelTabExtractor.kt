package org.schabi.newpipe.extractor.channel.tabs

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler

/**
 * A [ListExtractor] of [InfoItem]s for tabs of channels.
 */
abstract class ChannelTabExtractor(
    service: StreamingService,
    linkHandler: ListLinkHandler
) : ListExtractor<InfoItem>(service, linkHandler) {

    override fun getName(): String {
        return linkHandler.contentFilters[0]
    }
}
