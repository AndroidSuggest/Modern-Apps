package org.schabi.newpipe.extractor.services.youtube.extractors

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextFromObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getUrlFromObject
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.orEmptyObject

internal class YoutubeShowRendererInfoItemExtractor(
    showRenderer: JsonObject
) : YoutubeBaseShowInfoItemExtractor(showRenderer) {

    private val shortBylineText: JsonObject = showRenderer.getObject("shortBylineText").orEmptyObject()
    private val longBylineText: JsonObject = showRenderer.getObject("longBylineText").orEmptyObject()

    @Throws(ParsingException::class)
    override fun getUploaderName(): String? {
        var name = getTextFromObject(longBylineText)
        if (isNullOrEmpty(name)) {
            name = getTextFromObject(shortBylineText)
            if (isNullOrEmpty(name)) {
                throw ParsingException("Could not get uploader name")
            }
        }
        return name!!
    }

    @Throws(ParsingException::class)
    override fun getUploaderUrl(): String? {
        var uploaderUrl = getUrlFromObject(longBylineText)
        if (uploaderUrl == null) {
            uploaderUrl = getUrlFromObject(shortBylineText)
            if (uploaderUrl == null) {
                throw ParsingException("Could not get uploader URL")
            }
        }
        return uploaderUrl
    }

    @Throws(ParsingException::class)
    override fun isUploaderVerified(): Boolean = false
}
