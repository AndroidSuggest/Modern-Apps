package org.schabi.newpipe.extractor.linkhandler

import java.util.Collections

open class ListLinkHandler : LinkHandler {

    val contentFilters: List<String>
    val sortFilter: String

    constructor(
        originalUrl: String,
        url: String,
        id: String,
        contentFilters: List<String>,
        sortFilter: String
    ) : super(originalUrl, url, id) {
        this.contentFilters = Collections.unmodifiableList(contentFilters)
        this.sortFilter = sortFilter
    }

    constructor(handler: ListLinkHandler) : this(
        handler.originalUrl,
        handler.url,
        handler.id,
        handler.contentFilters,
        handler.sortFilter
    )

    constructor(handler: LinkHandler) : this(
        handler.originalUrl,
        handler.url,
        handler.id,
        Collections.emptyList(),
        ""
    )

    fun getContentFilters(): List<String> = contentFilters
    fun getSortFilter(): String = sortFilter
}
