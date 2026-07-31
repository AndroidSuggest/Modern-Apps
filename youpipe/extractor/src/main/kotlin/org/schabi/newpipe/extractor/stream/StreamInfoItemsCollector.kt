package org.schabi.newpipe.extractor.stream

import org.schabi.newpipe.extractor.InfoItemsCollector
import org.schabi.newpipe.extractor.exceptions.FoundAdException
import org.schabi.newpipe.extractor.exceptions.ParsingException

open class StreamInfoItemsCollector : InfoItemsCollector<StreamInfoItem, StreamInfoItemExtractor> {

    constructor(serviceId: Int) : super(serviceId)

    constructor(serviceId: Int, comparator: Comparator<StreamInfoItem>?) : super(serviceId, comparator)

    @Throws(ParsingException::class)
    override fun extract(extractor: StreamInfoItemExtractor): StreamInfoItem {
        if (extractor.isAd()) throw FoundAdException("Found ad")

        val resultItem = StreamInfoItem(
            getServiceId(), extractor.getUrl(), extractor.getName(), extractor.getStreamType()
        )

        try { resultItem.setDuration(extractor.getDuration()) } catch (e: Exception) { addError(e as Exception) }
        try { resultItem.setUploaderName(extractor.getUploaderName()) } catch (e: Exception) { addError(e as Exception) }
        try { resultItem.setTextualUploadDate(extractor.getTextualUploadDate()) } catch (e: Exception) { addError(e as Exception) }
        try { resultItem.setUploadDate(extractor.getUploadDate()) } catch (e: Exception) { addError(e as Exception) }
        try { resultItem.setViewCount(extractor.getViewCount()) } catch (e: Exception) { addError(e as Exception) }
        try { resultItem.setThumbnails(extractor.getThumbnails()) } catch (e: Exception) { addError(e as Exception) }
        try { resultItem.setUploaderUrl(extractor.getUploaderUrl()) } catch (e: Exception) { addError(e as Exception) }
        try { resultItem.setUploaderAvatars(extractor.getUploaderAvatars()) } catch (e: Exception) { addError(e as Exception) }
        try { resultItem.setUploaderVerified(extractor.isUploaderVerified()) } catch (e: Exception) { addError(e as Exception) }
        try { resultItem.setShortDescription(extractor.getShortDescription()) } catch (e: Exception) { addError(e as Exception) }
        try { resultItem.setShortFormContent(extractor.isShortFormContent()) } catch (e: Exception) { addError(e as Exception) }
        try { resultItem.setContentAvailability(extractor.getContentAvailability()) } catch (e: Exception) { addError(e as Exception) }

        return resultItem
    }

    override fun commit(extractor: StreamInfoItemExtractor) {
        try {
            addItem(extract(extractor))
        } catch (_: FoundAdException) {
        } catch (e: Exception) {
            addError(e)
        }
    }
}
