package org.schabi.newpipe.extractor.search

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.MetaInfo
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import javax.annotation.Nonnull

abstract class SearchExtractor(
    service: StreamingService,
    linkHandler: SearchQueryHandler
) : ListExtractor<InfoItem>(service, linkHandler) {

    class NothingFoundException(message: String) : ExtractionException(message)

    fun getSearchString(): String = linkHandler.getSearchString()

    @Nonnull
    @Throws(ParsingException::class)
    abstract fun getSearchSuggestion(): String

    override val linkHandler: SearchQueryHandler
        get() = super.linkHandler as SearchQueryHandler

    @Nonnull
    override fun getName(): String = linkHandler.getSearchString()

    @Throws(ParsingException::class)
    abstract fun isCorrectedSearch(): Boolean

    @Nonnull
    @Throws(ParsingException::class)
    abstract fun getMetaInfo(): List<MetaInfo>
}
