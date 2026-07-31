package org.schabi.newpipe.extractor

import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler

abstract class ListInfo<T : InfoItem> : Info {

    var relatedItems: List<T>? = null
    var nextPage: Page? = null
        private set
    val contentFilters: List<String>
    val sortFilter: String

    constructor(
        serviceId: Int,
        id: String,
        url: String,
        originalUrl: String,
        name: String,
        contentFilter: List<String>,
        sortFilter: String
    ) : super(serviceId, id, url, originalUrl, name) {
        this.contentFilters = contentFilter
        this.sortFilter = sortFilter
    }

    constructor(
        serviceId: Int,
        listUrlIdHandler: ListLinkHandler,
        name: String
    ) : super(serviceId, listUrlIdHandler, name) {
        this.contentFilters = listUrlIdHandler.contentFilters
        this.sortFilter = listUrlIdHandler.sortFilter
    }

    fun getRelatedItems(): List<T>? = relatedItems
    fun setRelatedItems(relatedItems: List<T>) {
        this.relatedItems = relatedItems
    }

    fun hasNextPage(): Boolean = Page.isValid(nextPage)
    fun getNextPage(): Page? = nextPage
    fun setNextPage(page: Page) {
        this.nextPage = page
    }

    fun getContentFilters(): List<String> = contentFilters
    fun getSortFilter(): String = sortFilter
}
