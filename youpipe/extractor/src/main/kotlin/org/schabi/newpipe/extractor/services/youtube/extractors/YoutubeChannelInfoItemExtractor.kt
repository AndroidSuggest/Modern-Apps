package org.schabi.newpipe.extractor.services.youtube.extractors

import kotlinx.serialization.json.JsonObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.channel.ChannelInfoItemExtractor
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextFromObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getThumbnailsFromInfoItem
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelLinkHandlerFactory
import org.schabi.newpipe.extractor.utils.Utils
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString

class YoutubeChannelInfoItemExtractor(
    private val channelInfoItem: JsonObject
) : ChannelInfoItemExtractor {

    /**
     * New layout:
     * "subscriberCountText": Channel handle
     * "videoCountText": Subscriber count
     */
    private val withHandle: Boolean

    init {
        var wHandle = false
        val subscriberCountText = getTextFromObject(channelInfoItem.getObject("subscriberCountText"))
        if (subscriberCountText != null) {
            wHandle = subscriberCountText.startsWith("@")
        }
        this.withHandle = wHandle
    }

    @Throws(ParsingException::class)
    override fun getThumbnails(): List<Image> {
        try {
            return getThumbnailsFromInfoItem(channelInfoItem)
        } catch (e: Exception) {
            throw ParsingException("Could not get thumbnails", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getName(): String {
        try {
            return getTextFromObject(channelInfoItem.getObject("title"))!!
        } catch (e: Exception) {
            throw ParsingException("Could not get name", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getUrl(): String {
        try {
            val id = "channel/" + channelInfoItem.getString("channelId")
            return YoutubeChannelLinkHandlerFactory.getInstance().getUrl(id)
        } catch (e: Exception) {
            throw ParsingException("Could not get url", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getSubscriberCount(): Long {
        try {
            if (!channelInfoItem.containsKey("subscriberCountText")) {
                // Subscription count is not available for this channel item.
                return -1
            }

            if (withHandle) {
                if (channelInfoItem.containsKey("videoCountText")) {
                    return Utils.mixedNumberWordToLong(
                        getTextFromObject(channelInfoItem.getObject("videoCountText"))
                    )
                } else {
                    return -1
                }
            }

            return Utils.mixedNumberWordToLong(
                getTextFromObject(channelInfoItem.getObject("subscriberCountText"))
            )
        } catch (e: Exception) {
            throw ParsingException("Could not get subscriber count", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getStreamCount(): Long {
        try {
            if (withHandle || !channelInfoItem.containsKey("videoCountText")) {
                // Video count is not available, either the channel has no public uploads
                // or YouTube displays the channel handle instead.
                return ListExtractor.ITEM_COUNT_UNKNOWN
            }

            return java.lang.Long.parseLong(
                Utils.removeNonDigitCharacters(
                    getTextFromObject(channelInfoItem.getObject("videoCountText"))
                )
            )
        } catch (e: Exception) {
            throw ParsingException("Could not get stream count", e)
        }
    }

    @Throws(ParsingException::class)
    override fun isVerified(): Boolean {
        return YoutubeParsingHelper.isVerified(channelInfoItem.getArray("ownerBadges")!!)
    }

    @Throws(ParsingException::class)
    override fun getDescription(): String? {
        try {
            if (!channelInfoItem.containsKey("descriptionSnippet")) {
                // Channel have no description.
                return null
            }

            return getTextFromObject(channelInfoItem.getObject("descriptionSnippet"))
        } catch (e: Exception) {
            throw ParsingException("Could not get description", e)
        }
    }
}
