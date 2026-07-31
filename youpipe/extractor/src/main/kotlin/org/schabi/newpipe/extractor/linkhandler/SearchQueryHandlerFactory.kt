package org.schabi.newpipe.extractor.linkhandler

import org.schabi.newpipe.extractor.exceptions.ParsingException
import java.util.Collections

abstract class SearchQueryHandlerFactory : ListLinkHandlerFactory() {

    @Throws(ParsingException::class, UnsupportedOperationException::class)
    abstract override fun getUrl(query: String, contentFilter: List<String>, sortFilter: String): String

    open fun getSearchString(url: String): String = ""

    @Throws(ParsingException::class, UnsupportedOperationException::class)
    override fun getId(url: String): String = getSearchString(url)

    @Throws(ParsingException::class)
    override fun fromQuery(query: String, contentFilter: List<String>, sortFilter: String): SearchQueryHandler =
        SearchQueryHandler(super.fromQuery(query, contentFilter, sortFilter))

    @Throws(ParsingException::class)
    fun fromQuery(query: String): SearchQueryHandler =
        fromQuery(query, Collections.emptyList(), "")

    override fun onAcceptUrl(url: String): Boolean = false
}
