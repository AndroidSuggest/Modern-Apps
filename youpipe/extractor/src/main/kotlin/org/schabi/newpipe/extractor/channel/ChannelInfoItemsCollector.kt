package org.schabi.newpipe.extractor.channel

import org.schabi.newpipe.extractor.InfoItemsCollector
import org.schabi.newpipe.extractor.exceptions.ParsingException

class ChannelInfoItemsCollector(
    serviceId: Int
) : InfoItemsCollector<ChannelInfoItem, ChannelInfoItemExtractor>(serviceId) {

    @Throws(ParsingException::class)
    override fun extract(extractor: ChannelInfoItemExtractor): ChannelInfoItem {
        val resultItem = ChannelInfoItem(
            getServiceId(), extractor.getUrl(), extractor.getName()
        )

        // optional information
        try {
            resultItem.setSubscriberCount(extractor.getSubscriberCount())
        } catch (e: Exception) {
            addError(e)
        }
        try {
            resultItem.setStreamCount(extractor.getStreamCount())
        } catch (e: Exception) {
            addError(e)
        }
        try {
            resultItem.setThumbnails(extractor.getThumbnails())
        } catch (e: Exception) {
            addError(e)
        }
        try {
            resultItem.setDescription(extractor.getDescription())
        } catch (e: Exception) {
            addError(e)
        }
        try {
            resultItem.setVerified(extractor.isVerified())
        } catch (e: Exception) {
            addError(e)
        }

        return resultItem
    }
}
