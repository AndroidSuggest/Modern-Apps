package org.schabi.newpipe.extractor.services.youtube.extractors

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getImagesFromThumbnailsArray
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextFromObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getUrlFromNavigationEndpoint
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.utils.Parser
import org.schabi.newpipe.extractor.utils.Utils
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString

class YoutubeMusicSongOrVideoInfoItemExtractor(
    private val songOrVideoInfoItem: JsonObject,
    private val descriptionElements: JsonArray,
    private val searchType: String
) : StreamInfoItemExtractor {

    @Throws(ParsingException::class)
    override fun getUrl(): String {
        val id = songOrVideoInfoItem.getObject("playlistItemData")!!.getString("videoId")
        if (!isNullOrEmpty(id)) {
            return "https://music.youtube.com/watch?v=$id"
        }
        throw ParsingException("Could not get URL")
    }

    @Throws(ParsingException::class)
    override fun getName(): String {
        val name = getTextFromObject(
            songOrVideoInfoItem.getArray("flexColumns")!!
                .getObject(0)!!
                .getObject("musicResponsiveListItemFlexColumnRenderer")!!
                .getObject("text")
        )
        if (!isNullOrEmpty(name)) {
            return name
        }
        throw ParsingException("Could not get name")
    }

    override fun getStreamType(): StreamType = StreamType.VIDEO_STREAM

    override fun isAd(): Boolean = false

    @Throws(ParsingException::class)
    override fun getDuration(): Long {
        val duration = descriptionElements.getObject(descriptionElements.size - 1)!!
            .getString("text")
        if (!isNullOrEmpty(duration)) {
            return YoutubeParsingHelper.parseDurationString(duration)
        }
        throw ParsingException("Could not get duration")
    }

    @Throws(ParsingException::class)
    override fun getUploaderName(): String {
        val name = descriptionElements.getObject(0)!!.getString("text")
        if (!isNullOrEmpty(name)) {
            return name!!
        }
        throw ParsingException("Could not get uploader name")
    }

    @Throws(ParsingException::class)
    override fun getUploaderUrl(): String? {
        if (searchType == MUSIC_VIDEOS) {
            val items = songOrVideoInfoItem.getObject("menu")!!
                .getObject("menuRenderer")!!
                .getArray("items")!!
            for (item in items) {
                val menuNavigationItemRenderer =
                    (item as JsonObject).getObject("menuNavigationItemRenderer")!!
                if (menuNavigationItemRenderer.getObject("icon")!!
                        .getString("iconType", "") == "ARTIST"
                ) {
                    return getUrlFromNavigationEndpoint(
                        menuNavigationItemRenderer.getObject("navigationEndpoint")!!
                    )
                }
            }
            return null
        } else {
            val navigationEndpointHolder = songOrVideoInfoItem.getArray("flexColumns")!!
                .getObject(1)!!
                .getObject("musicResponsiveListItemFlexColumnRenderer")!!
                .getObject("text")!!
                .getArray("runs")!!
                .getObject(0)!!

            if (!navigationEndpointHolder.containsKey("navigationEndpoint")) {
                return null
            }

            val url = getUrlFromNavigationEndpoint(
                navigationEndpointHolder.getObject("navigationEndpoint")!!
            )

            if (!isNullOrEmpty(url)) {
                return url
            }

            throw ParsingException("Could not get uploader URL")
        }
    }

    override fun isUploaderVerified(): Boolean = false

    override fun getTextualUploadDate(): String? = null

    override fun getUploadDate() = null

    @Throws(ParsingException::class)
    override fun getViewCount(): Long {
        if (searchType == MUSIC_SONGS) {
            return -1
        }
        val viewCount = descriptionElements
            .getObject(descriptionElements.size - 3)!!
            .getString("text")
        if (!isNullOrEmpty(viewCount)) {
            return try {
                Utils.mixedNumberWordToLong(viewCount)
            } catch (e: Parser.RegexException) {
                0
            }
        }
        throw ParsingException("Could not get view count")
    }

    @Throws(ParsingException::class)
    override fun getThumbnails(): List<Image> {
        return try {
            getImagesFromThumbnailsArray(
                songOrVideoInfoItem.getObject("thumbnail")!!
                    .getObject("musicThumbnailRenderer")!!
                    .getObject("thumbnail")!!
                    .getArray("thumbnails")!!
            )
        } catch (e: Exception) {
            throw ParsingException("Could not get thumbnails", e)
        }
    }
}
