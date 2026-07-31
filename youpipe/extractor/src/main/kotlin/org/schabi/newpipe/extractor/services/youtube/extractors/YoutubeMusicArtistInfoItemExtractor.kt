package org.schabi.newpipe.extractor.services.youtube.extractors

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.channel.ChannelInfoItemExtractor
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getImagesFromThumbnailsArray
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextFromObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getUrlFromNavigationEndpoint
import org.schabi.newpipe.extractor.utils.Utils
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import org.schabi.newpipe.extractor.utils.orEmptyArray
import org.schabi.newpipe.extractor.utils.orEmptyObject

class YoutubeMusicArtistInfoItemExtractor(
    private val artistInfoItem: JsonObject
) : ChannelInfoItemExtractor {

    @Throws(ParsingException::class)
    override fun getThumbnails(): List<Image> {
        return try {
            getImagesFromThumbnailsArray(
                artistInfoItem.getObject("thumbnail").orEmptyObject()
                    .getObject("musicThumbnailRenderer").orEmptyObject()
                    .getObject("thumbnail").orEmptyObject()
                    .getArray("thumbnails").orEmptyArray()
            )
        } catch (e: Exception) {
            throw ParsingException("Could not get thumbnails", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getName(): String {
        val name = getTextFromObject(
            artistInfoItem.getArray("flexColumns").orEmptyArray()
                .getObject(0).orEmptyObject()
                .getObject("musicResponsiveListItemFlexColumnRenderer").orEmptyObject()
                .getObject("text")
        )
        if (!isNullOrEmpty(name)) {
            return name
        }
        throw ParsingException("Could not get name")
    }

    @Throws(ParsingException::class)
    override fun getUrl(): String {
        val url = getUrlFromNavigationEndpoint(
            artistInfoItem.getObject("navigationEndpoint").orEmptyObject()
        )
        if (!isNullOrEmpty(url)) {
            return url
        }
        throw ParsingException("Could not get URL")
    }

    @Throws(ParsingException::class)
    override fun getSubscriberCount(): Long {
        val flexColumns = artistInfoItem.getArray("flexColumns").orEmptyArray()
        val runs = flexColumns
            .getObject(flexColumns.size - 1).orEmptyObject()
            .getObject("musicResponsiveListItemFlexColumnRenderer").orEmptyObject()
            .getObject("text").orEmptyObject()
            .getArray("runs").orEmptyArray()
        val subscriberCount = runs.getObject(runs.size - 1).orEmptyObject()
            .getString("text")
        if (!isNullOrEmpty(subscriberCount)) {
            return try {
                Utils.mixedNumberWordToLong(subscriberCount)
            } catch (ignored: Exception) {
                0
            }
        }
        throw ParsingException("Could not get subscriber count")
    }

    override fun getStreamCount(): Long = -1

    override fun isVerified(): Boolean = true

    override fun getDescription(): String? = null
}
