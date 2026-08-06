package org.schabi.newpipe.extractor.linkhandler

import org.schabi.newpipe.extractor.exceptions.ParsingException

abstract class SearchQueryHandlerFactory : ListLinkHandlerFactory() {

    @Throws(ParsingException::class, UnsupportedOperationException::class)
    abstract override fun getUrl(id: String, contentFilter: List<String>, sortFilter: String): String

    open fun getSearchString(url: String): String = ""

    @Throws(ParsingException::class, UnsupportedOperationException::class)
    override fun getId(url: String): String = getSearchString(url)

    @Throws(ParsingException::class)
    override fun fromQuery(id: String, contentFilters: List<String>, sortFilter: String): SearchQueryHandler =
        SearchQueryHandler(super.fromQuery(id, contentFilters, sortFilter))

    @Throws(ParsingException::class)
    fun fromQuery(query: String): SearchQueryHandler =
        fromQuery(query, emptyList(), "")

    override fun onAcceptUrl(url: String): Boolean = false
}
