package org.schabi.newpipe.extractor.playlist

import org.schabi.newpipe.extractor.InfoItemsCollector
import org.schabi.newpipe.extractor.exceptions.ParsingException

class PlaylistInfoItemsCollector(
    serviceId: Int
) : InfoItemsCollector<PlaylistInfoItem, PlaylistInfoItemExtractor>(serviceId) {

    @Throws(ParsingException::class)
    override fun extract(extractor: PlaylistInfoItemExtractor): PlaylistInfoItem {
        val resultItem = PlaylistInfoItem(
            getServiceId(), extractor.getUrl(), extractor.getName()
        )

        try {
            resultItem.setUploaderName(extractor.getUploaderName())
        } catch (e: Exception) {
            addError(e)
        }
        try {
            resultItem.setUploaderUrl(extractor.getUploaderUrl())
        } catch (e: Exception) {
            addError(e)
        }
        try {
            resultItem.setUploaderVerified(extractor.isUploaderVerified())
        } catch (e: Exception) {
            addError(e)
        }
        try {
            resultItem.setThumbnails(extractor.getThumbnails())
        } catch (e: Exception) {
            addError(e)
        }
        try {
            resultItem.setStreamCount(extractor.getStreamCount())
        } catch (e: Exception) {
            addError(e)
        }
        try {
            resultItem.setDescription(extractor.getDescription())
        } catch (e: Exception) {
            addError(e)
        }
        try {
            resultItem.setPlaylistType(extractor.getPlaylistType())
        } catch (e: Exception) {
            addError(e)
        }
        return resultItem
    }
}
