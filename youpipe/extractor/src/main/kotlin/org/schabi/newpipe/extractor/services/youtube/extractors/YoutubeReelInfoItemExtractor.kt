package org.schabi.newpipe.extractor.services.youtube.extractors

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextFromObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getThumbnailsFromInfoItem
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.utils.Utils
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import org.schabi.newpipe.extractor.utils.orEmptyObject

/**
 * A [StreamInfoItemExtractor] for YouTube's `reelItemRenderer`s.
 *
 * `reelItemRenderer`s were returned on YouTube for their short-form contents on almost every
 * place and every major client. They provide a limited amount of information and do not provide
 * the exact view count, any uploader info (name, URL, avatar, verified status) and the upload date.
 *
 * At the time this documentation has been updated, they are being replaced by
 * `shortsLockupViewModel`s. See [YoutubeShortsLockupInfoItemExtractor] for an
 * extractor for this new UI data type.
 */
open class YoutubeReelInfoItemExtractor(
    private val reelInfo: JsonObject
) : StreamInfoItemExtractor {

    @Throws(ParsingException::class)
    override fun getName(): String {
        return getTextFromObject(reelInfo.getObject("headline").orEmptyObject())!!
    }

    @Throws(ParsingException::class)
    override fun getUrl(): String {
        try {
            val videoId = reelInfo.getString("videoId")!!
            return YoutubeStreamLinkHandlerFactory.getInstance().getUrl(videoId)
        } catch (e: Exception) {
            throw ParsingException("Could not get URL", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getThumbnails(): List<Image> {
        return getThumbnailsFromInfoItem(reelInfo)
    }

    @Throws(ParsingException::class)
    override fun getStreamType(): StreamType = StreamType.VIDEO_STREAM

    @Throws(ParsingException::class)
    override fun getViewCount(): Long {
        val viewCountText = getTextFromObject(reelInfo.getObject("viewCountText"))
        if (!isNullOrEmpty(viewCountText)) {
            if (viewCountText.lowercase().contains("no views")) {
                return 0
            }
            return Utils.mixedNumberWordToLong(viewCountText)
        }
        throw ParsingException("Could not get short view count")
    }

    override fun isShortFormContent(): Boolean = true

    @Throws(ParsingException::class)
    override fun isAd(): Boolean = false

    @Throws(ParsingException::class)
    override fun getDuration(): Long = -1

    @Throws(ParsingException::class)
    override fun getUploaderName(): String? = null

    @Throws(ParsingException::class)
    override fun getUploaderUrl(): String? = null

    @Throws(ParsingException::class)
    override fun isUploaderVerified(): Boolean = false

    @Throws(ParsingException::class)
    override fun getTextualUploadDate(): String? = null

    @Throws(ParsingException::class)
    override fun getUploadDate() = null
}
