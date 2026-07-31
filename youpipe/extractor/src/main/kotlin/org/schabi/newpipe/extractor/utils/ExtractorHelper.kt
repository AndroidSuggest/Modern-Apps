package org.schabi.newpipe.extractor.utils

import org.schabi.newpipe.extractor.Info
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamInfo

object ExtractorHelper {

    @JvmStatic
    fun <T : InfoItem> getItemsPageOrLogError(
        info: Info,
        extractor: ListExtractor<T>
    ): InfoItemsPage<T> {
        return try {
            val page = extractor.getInitialPage()
            info.addAllErrors(page.errors)
            page
        } catch (e: Exception) {
            info.addError(e)
            InfoItemsPage.emptyPage()
        }
    }

    @JvmStatic
    fun getRelatedItemsOrLogError(info: StreamInfo, extractor: StreamExtractor): List<InfoItem> {
        return try {
            val collector = extractor.getRelatedItems() ?: return emptyList()
            info.addAllErrors(collector.getErrors())
            @Suppress("UNCHECKED_CAST")
            collector.getItems() as List<InfoItem>
        } catch (e: Exception) {
            info.addError(e)
            emptyList()
        }
    }

    /**
     * @deprecated Use [getRelatedItemsOrLogError]
     */
    @Deprecated("Use getRelatedItemsOrLogError")
    @JvmStatic
    fun getRelatedVideosOrLogError(info: StreamInfo, extractor: StreamExtractor): List<InfoItem> =
        getRelatedItemsOrLogError(info, extractor)
}
