package org.schabi.newpipe.extractor.kiosk

import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.ListInfo
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.utils.ExtractorHelper
import java.io.IOException

class KioskInfo private constructor(
    serviceId: Int,
    linkHandler: ListLinkHandler,
    name: String
) : ListInfo<StreamInfoItem>(serviceId, linkHandler, name) {

    companion object {
        @JvmStatic
        @Throws(IOException::class, ExtractionException::class)
        fun getMoreItems(
            service: StreamingService,
            url: String,
            page: Page
        ): ListExtractor.InfoItemsPage<StreamInfoItem> {
            return service.getKioskList().getExtractorByUrl(url, page).getPage(page)
        }

        @JvmStatic
        @Throws(IOException::class, ExtractionException::class)
        fun getInfo(url: String): KioskInfo {
            return getInfo(NewPipe.getServiceByUrl(url), url)
        }

        @JvmStatic
        @Throws(IOException::class, ExtractionException::class)
        fun getInfo(service: StreamingService, url: String): KioskInfo {
            val extractor = service.getKioskList().getExtractorByUrl(url, null)
            extractor.fetchPage()
            return getInfo(extractor)
        }

        /**
         * Get KioskInfo from KioskExtractor
         *
         * @param extractor an extractor where fetchPage() was already got called on.
         */
        @JvmStatic
        @Throws(ExtractionException::class)
        fun getInfo(extractor: KioskExtractor<*>): KioskInfo {
            val info = KioskInfo(
                extractor.getServiceId(),
                extractor.linkHandler,
                extractor.getName()
            )

            val itemsPage: ListExtractor.InfoItemsPage<StreamInfoItem> =
                ExtractorHelper.getItemsPageOrLogError(info, extractor as ListExtractor<StreamInfoItem>)
            info.setRelatedItems(itemsPage.getItems())
            itemsPage.getNextPage()?.let { info.setNextPage(it) }

            return info
        }
    }
}
