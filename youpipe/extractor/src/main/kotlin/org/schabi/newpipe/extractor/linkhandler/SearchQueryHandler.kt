package org.schabi.newpipe.extractor.linkhandler

open class SearchQueryHandler : ListLinkHandler {

    constructor(
        originalUrl: String,
        url: String,
        searchString: String,
        contentFilters: List<String>,
        sortFilter: String
    ) : super(originalUrl, url, searchString, contentFilters, sortFilter)

    constructor(handler: ListLinkHandler) : this(
        handler.originalUrl,
        handler.url,
        handler.id,
        handler.contentFilters,
        handler.sortFilter
    )

    fun getSearchString(): String = id
}
