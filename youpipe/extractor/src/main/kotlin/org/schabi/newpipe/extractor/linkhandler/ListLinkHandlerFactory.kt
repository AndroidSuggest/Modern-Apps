package org.schabi.newpipe.extractor.linkhandler

import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.utils.Utils

abstract class ListLinkHandlerFactory : LinkHandlerFactory() {

    @Throws(ParsingException::class, UnsupportedOperationException::class)
    abstract fun getUrl(id: String, contentFilter: List<String>, sortFilter: String): String

    @Throws(ParsingException::class, UnsupportedOperationException::class)
    open fun getUrl(
        id: String,
        contentFilter: List<String>,
        sortFilter: String,
        baseUrl: String
    ): String = getUrl(id, contentFilter, sortFilter)

    override fun fromUrl(url: String): ListLinkHandler {
        val polishedUrl = Utils.followGoogleRedirectIfNeeded(url)
        val baseUrl = Utils.getBaseUrl(polishedUrl)
        return fromUrl(polishedUrl, baseUrl)
    }

    override fun fromUrl(url: String, baseUrl: String): ListLinkHandler {
        return ListLinkHandler(super.fromUrl(url, baseUrl))
    }

    override fun fromId(id: String): ListLinkHandler = ListLinkHandler(super.fromId(id))

    override fun fromId(id: String, baseUrl: String): ListLinkHandler =
        ListLinkHandler(super.fromId(id, baseUrl))

    @Throws(ParsingException::class)
    open fun fromQuery(id: String, contentFilters: List<String>, sortFilter: String): ListLinkHandler {
        val url = getUrl(id, contentFilters, sortFilter)
        return ListLinkHandler(url, url, id, contentFilters, sortFilter)
    }

    @Throws(ParsingException::class)
    fun fromQuery(
        id: String,
        contentFilters: List<String>,
        sortFilter: String,
        baseUrl: String
    ): ListLinkHandler {
        val url = getUrl(id, contentFilters, sortFilter, baseUrl)
        return ListLinkHandler(url, url, id, contentFilters, sortFilter)
    }

    @Throws(ParsingException::class, UnsupportedOperationException::class)
    override fun getUrl(id: String): String = getUrl(id, ArrayList(0), "")

    override fun getUrl(id: String, baseUrl: String): String =
        getUrl(id, ArrayList(0), "", baseUrl)

    open fun getAvailableContentFilter(): Array<String> = emptyArray()

    open fun getAvailableSortFilter(): Array<String> = emptyArray()
}
