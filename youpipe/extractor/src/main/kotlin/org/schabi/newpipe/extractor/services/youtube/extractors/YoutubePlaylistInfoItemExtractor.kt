package org.schabi.newpipe.extractor.services.youtube.extractors

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItemExtractor
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getImagesFromThumbnailsArray
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextFromObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getUrlFromObject
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubePlaylistLinkHandlerFactory
import org.schabi.newpipe.extractor.utils.Utils
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import org.schabi.newpipe.extractor.utils.orEmptyArray
import org.schabi.newpipe.extractor.utils.orEmptyObject

open class YoutubePlaylistInfoItemExtractor(
    private val playlistInfoItem: JsonObject
) : PlaylistInfoItemExtractor {

    @Throws(ParsingException::class)
    override fun getThumbnails(): List<Image> {
        try {
            var thumbnails: JsonArray? = playlistInfoItem.getArray("thumbnails").orEmptyArray()
                .getObject(0).orEmptyObject()
                .getArray("thumbnails")
            if (thumbnails == null || thumbnails.isEmpty()) {
                thumbnails = playlistInfoItem.getObject("thumbnail").orEmptyObject()
                    .getArray("thumbnails")
            }
            return getImagesFromThumbnailsArray(thumbnails!!)
        } catch (e: Exception) {
            throw ParsingException("Could not get thumbnails", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getName(): String {
        try {
            return getTextFromObject(playlistInfoItem.getObject("title").orEmptyObject())!!
        } catch (e: Exception) {
            throw ParsingException("Could not get name", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getUrl(): String {
        try {
            val id = playlistInfoItem.getString("playlistId")!!
            return YoutubePlaylistLinkHandlerFactory.getInstance().getUrl(id)
        } catch (e: Exception) {
            throw ParsingException("Could not get url", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getUploaderName(): String? {
        try {
            return getTextFromObject(playlistInfoItem.getObject("longBylineText").orEmptyObject())!!
        } catch (e: Exception) {
            throw ParsingException("Could not get uploader name", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getUploaderUrl(): String? {
        try {
            return getUrlFromObject(playlistInfoItem.getObject("longBylineText").orEmptyObject())!!
        } catch (e: Exception) {
            throw ParsingException("Could not get uploader url", e)
        }
    }

    @Throws(ParsingException::class)
    override fun isUploaderVerified(): Boolean {
        try {
            return YoutubeParsingHelper.isVerified(playlistInfoItem.getArray("ownerBadges").orEmptyArray())
        } catch (e: Exception) {
            throw ParsingException("Could not get uploader verification info", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getStreamCount(): Long {
        var videoCountText = playlistInfoItem.getString("videoCount")
        if (videoCountText == null) {
            videoCountText = getTextFromObject(playlistInfoItem.getObject("videoCountText"))
        }
        if (videoCountText == null) {
            videoCountText = getTextFromObject(playlistInfoItem.getObject("videoCountShortText"))
        }
        if (videoCountText == null) {
            throw ParsingException("Could not get stream count")
        }
        try {
            return java.lang.Long.parseLong(Utils.removeNonDigitCharacters(videoCountText))
        } catch (e: Exception) {
            throw ParsingException("Could not get stream count", e)
        }
    }
}
