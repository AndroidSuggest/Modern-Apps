package org.schabi.newpipe.extractor.linkhandler

import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.utils.Utils
import java.io.Serializable

open class LinkHandler(
    val originalUrl: String,
    val url: String,
    val id: String
) : Serializable {

    constructor(handler: LinkHandler) : this(handler.originalUrl, handler.url, handler.id)

    fun getOriginalUrl(): String = originalUrl
    fun getUrl(): String = url
    fun getId(): String = id

    @Throws(ParsingException::class)
    fun getBaseUrl(): String = Utils.getBaseUrl(url)
}
