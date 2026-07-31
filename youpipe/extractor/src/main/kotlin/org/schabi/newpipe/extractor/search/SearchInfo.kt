package org.schabi.newpipe.extractor.search

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.ListInfo
import org.schabi.newpipe.extractor.MetaInfo
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.utils.ExtractorHelper
import java.io.IOException
import javax.annotation.Nonnull

class SearchInfo(
    serviceId: Int,
    qIHandler: SearchQueryHandler,
    private val searchString: String
) : ListInfo<InfoItem>(serviceId, qIHandler, "Search") {

    private var searchSuggestion: String? = null
    private var isCorrectedSearch: Boolean = false

    @Nonnull
    private var metaInfo: List<MetaInfo> = emptyList()

    fun getSearchString(): String = searchString

    fun getSearchSuggestion(): String? = searchSuggestion

    fun isCorrectedSearch(): Boolean = isCorrectedSearch

    fun setIsCorrectedSearch(isCorrectedSearch: Boolean) {
        this.isCorrectedSearch = isCorrectedSearch
    }

    fun setSearchSuggestion(searchSuggestion: String?) {
        this.searchSuggestion = searchSuggestion
    }

    @Nonnull
    fun getMetaInfo(): List<MetaInfo> = metaInfo

    fun setMetaInfo(@Nonnull metaInfo: List<MetaInfo>) {
        this.metaInfo = metaInfo
    }

    companion object {
        @JvmStatic
        @Throws(ExtractionException::class, IOException::class)
        fun getInfo(
            service: StreamingService,
            searchQuery: SearchQueryHandler
        ): SearchInfo {
            val extractor = service.getSearchExtractor(searchQuery)
            extractor.fetchPage()
            return getInfo(extractor)
        }

        @JvmStatic
        @Throws(ExtractionException::class, IOException::class)
        fun getInfo(extractor: SearchExtractor): SearchInfo {
            val info = SearchInfo(
                extractor.getServiceId(),
                extractor.linkHandler,
                extractor.getSearchString()
            )

            try {
                info.setOriginalUrl(extractor.getOriginalUrl())
            } catch (e: Exception) {
                info.addError(e)
            }
            try {
                info.setSearchSuggestion(extractor.getSearchSuggestion())
            } catch (e: Exception) {
                info.addError(e)
            }
            try {
                info.setIsCorrectedSearch(extractor.isCorrectedSearch())
            } catch (e: Exception) {
                info.addError(e)
            }
            try {
                info.setMetaInfo(extractor.getMetaInfo())
            } catch (e: Exception) {
                info.addError(e)
            }

            val page = ExtractorHelper.getItemsPageOrLogError(info, extractor)
            info.relatedItems = page.getItems()
            page.nextPage?.let { info.setNextPage(it) }

            return info
        }

        @JvmStatic
        @Throws(IOException::class, ExtractionException::class)
        fun getMoreItems(
            service: StreamingService,
            query: SearchQueryHandler,
            page: Page
        ): ListExtractor.InfoItemsPage<InfoItem> {
            return service.getSearchExtractor(query).getPage(page)
        }
    }
}
