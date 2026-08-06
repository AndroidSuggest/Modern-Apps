package org.schabi.newpipe.extractor.services.youtube.extractors

import kotlinx.serialization.json.JsonObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItemExtractor
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.extractPlaylistTypeFromPlaylistUrl
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextFromObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getThumbnailsFromInfoItem
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString

class YoutubeMixOrPlaylistInfoItemExtractor(
    private val mixInfoItem: JsonObject
) : PlaylistInfoItemExtractor {

    @Throws(ParsingException::class)
    override fun getName(): String {
        val name = getTextFromObject(mixInfoItem.getObject("title"))
        if (isNullOrEmpty(name)) {
            throw ParsingException("Could not get name")
        }
        return name
    }

    @Throws(ParsingException::class)
    override fun getUrl(): String {
        val url = mixInfoItem.getString("shareUrl")
        if (isNullOrEmpty(url)) {
            throw ParsingException("Could not get url")
        }
        return url
    }

    override fun getThumbnails(): List<Image> {
        return getThumbnailsFromInfoItem(mixInfoItem)
    }

    @Throws(ParsingException::class)
    override fun getUploaderName(): String? {
        // this will be a list of uploaders for mixes
        return YoutubeParsingHelper.getTextFromObject(mixInfoItem.getObject("longBylineText")) ?: ""
    }

    @Throws(ParsingException::class)
    override fun getUploaderUrl(): String? {
        // They're auto-generated, so there's no uploader
        return null
    }

    @Throws(ParsingException::class)
    override fun isUploaderVerified(): Boolean {
        // They're auto-generated, so there's no uploader
        return false
    }

    @Throws(ParsingException::class)
    override fun getStreamCount(): Long {
        val countString = YoutubeParsingHelper.getTextFromObject(
            mixInfoItem.getObject("videoCountShortText")
        ) ?: throw ParsingException("Could not extract item count for playlist/mix info item")

        return try {
            countString.toInt().toLong()
        } catch (ignored: NumberFormatException) {
            // un-parsable integer: this is a mix with infinite items and "50+" as count string
            ListExtractor.ITEM_COUNT_INFINITE
        }
    }

    override fun getPlaylistType(): PlaylistInfo.PlaylistType {
        return extractPlaylistTypeFromPlaylistUrl(getUrl())
    }
}
