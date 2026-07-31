package org.schabi.newpipe.extractor.linkhandler

import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.utils.Utils
import java.util.Objects

abstract class LinkHandlerFactory {

    @Throws(ParsingException::class, UnsupportedOperationException::class)
    abstract fun getId(url: String): String

    @Throws(ParsingException::class, UnsupportedOperationException::class)
    abstract fun getUrl(id: String): String

    @Throws(ParsingException::class)
    abstract fun onAcceptUrl(url: String): Boolean

    @Throws(ParsingException::class, UnsupportedOperationException::class)
    open fun getUrl(id: String, baseUrl: String): String = getUrl(id)

    @Throws(ParsingException::class)
    fun fromUrl(url: String): LinkHandler {
        if (Utils.isNullOrEmpty(url)) {
            throw IllegalArgumentException("The url is null or empty")
        }
        val polishedUrl = Utils.followGoogleRedirectIfNeeded(url)
        val baseUrl = Utils.getBaseUrl(polishedUrl)
        return fromUrl(polishedUrl, baseUrl)
    }

    @Throws(ParsingException::class)
    open fun fromUrl(url: String, baseUrl: String): LinkHandler {
        Objects.requireNonNull(url, "URL cannot be null")
        if (!acceptUrl(url)) {
            throw ParsingException("URL not accepted: $url")
        }
        val id = getId(url)
        return LinkHandler(url, getUrl(id, baseUrl), id)
    }

    @Throws(ParsingException::class)
    fun fromId(id: String): LinkHandler {
        Objects.requireNonNull(id, "ID cannot be null")
        val url = getUrl(id)
        return LinkHandler(url, url, id)
    }

    @Throws(ParsingException::class)
    fun fromId(id: String, baseUrl: String): LinkHandler {
        Objects.requireNonNull(id, "ID cannot be null")
        val url = getUrl(id, baseUrl)
        return LinkHandler(url, url, id)
    }

    @Throws(ParsingException::class)
    fun acceptUrl(url: String): Boolean = onAcceptUrl(url)
}
