package org.schabi.newpipe.extractor.services.youtube.extractors

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getImagesFromThumbnailsArray
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.utils.Utils
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import org.schabi.newpipe.extractor.utils.orEmptyArray
import org.schabi.newpipe.extractor.utils.orEmptyObject

internal open class YoutubeShortsLockupInfoItemExtractor(
    private val shortsLockupViewModel: JsonObject
) : StreamInfoItemExtractor {

    @Throws(ParsingException::class)
    override fun getName(): String {
        return shortsLockupViewModel.getObject("overlayMetadata").orEmptyObject()
            .getObject("primaryText").orEmptyObject()
            .getString("content")
            ?: throw ParsingException("Could not get name")
    }

    @Throws(ParsingException::class)
    override fun getUrl(): String {
        var videoId = shortsLockupViewModel.getObject("onTap").orEmptyObject()
            .getObject("innertubeCommand").orEmptyObject()
            .getObject("reelWatchEndpoint").orEmptyObject()
            .getString("videoId")

        if (isNullOrEmpty(videoId)) {
            videoId = shortsLockupViewModel.getObject("inlinePlayerData").orEmptyObject()
                .getObject("onVisible").orEmptyObject()
                .getObject("innertubeCommand").orEmptyObject()
                .getObject("watchEndpoint").orEmptyObject()
                .getString("videoId")
        }

        if (isNullOrEmpty(videoId)) {
            throw ParsingException("Could not get video ID")
        }

        try {
            return YoutubeStreamLinkHandlerFactory.getInstance().getUrl(videoId!!)
        } catch (e: Exception) {
            throw ParsingException("Could not get URL", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getThumbnails(): List<Image> {
        return if (shortsLockupViewModel.containsKey("thumbnail")) {
            getImagesFromThumbnailsArray(
                shortsLockupViewModel.getObject("thumbnail").orEmptyObject()
                    .getArray("sources").orEmptyArray()
            )
        } else {
            getImagesFromThumbnailsArray(
                shortsLockupViewModel
                    .getObject("thumbnailViewModel").orEmptyObject()
                    .getObject("thumbnailViewModel").orEmptyObject()
                    .getObject("image").orEmptyObject()
                    .getArray("sources").orEmptyArray()
            )
        }
    }

    @Throws(ParsingException::class)
    override fun getStreamType(): StreamType = StreamType.VIDEO_STREAM

    @Throws(ParsingException::class)
    override fun getViewCount(): Long {
        val viewCountText = shortsLockupViewModel.getObject("overlayMetadata").orEmptyObject()
            .getObject("secondaryText").orEmptyObject()
            .getString("content")
        if (!isNullOrEmpty(viewCountText)) {
            if (viewCountText!!.contains("✪")) {
                return -1
            }
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
