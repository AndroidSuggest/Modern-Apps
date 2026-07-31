package org.schabi.newpipe.extractor.services.youtube.extractors

import kotlinx.serialization.json.JsonObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItemExtractor
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextFromObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getThumbnailsFromInfoItem
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getUrlFromNavigationEndpoint
import org.schabi.newpipe.extractor.utils.Utils
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString

/**
 * The base [PlaylistInfoItemExtractor] for shows playlists UI elements.
 */
internal abstract class YoutubeBaseShowInfoItemExtractor protected constructor(
    protected val showRenderer: JsonObject
) : PlaylistInfoItemExtractor {

    @Throws(ParsingException::class)
    override fun getName(): String {
        return showRenderer.getString("title")
            ?: throw ParsingException("Could not get name")
    }

    @Throws(ParsingException::class)
    override fun getUrl(): String {
        return getUrlFromNavigationEndpoint(
            showRenderer.getObject("navigationEndpoint")
                ?: throw ParsingException("Could not get navigationEndpoint")
        )
    }

    @Throws(ParsingException::class)
    override fun getThumbnails(): List<Image> {
        return getThumbnailsFromInfoItem(
            showRenderer.getObject("thumbnailRenderer")!!
                .getObject("showCustomThumbnailRenderer")!!
        )
    }

    @Throws(ParsingException::class)
    override fun getStreamCount(): Long {
        // The stream count should be always returned in the first text object for English
        // localizations, but the complete text is parsed for reliability purposes
        val streamCountText = getTextFromObject(
            showRenderer.getObject("thumbnailOverlays")!!
                .getObject("thumbnailOverlayBottomPanelRenderer")!!
                .getObject("text")
        )
        if (streamCountText == null) {
            throw ParsingException("Could not get stream count")
        }

        try {
            // The data returned could be a human/shortened number, but no show with more than 1000
            // videos has been found at the time this code was written
            return java.lang.Long.parseLong(Utils.removeNonDigitCharacters(streamCountText))
        } catch (e: NumberFormatException) {
            throw ParsingException("Could not convert stream count to a long", e)
        }
    }
}
