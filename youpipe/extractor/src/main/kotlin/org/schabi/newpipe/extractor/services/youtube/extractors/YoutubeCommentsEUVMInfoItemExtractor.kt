package org.schabi.newpipe.extractor.services.youtube.extractors

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.comments.CommentsInfoItemExtractor
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.localization.TimeAgoParser
import org.schabi.newpipe.extractor.services.youtube.YoutubeDescriptionHelper.attributedDescriptionToHtml
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getImagesFromThumbnailsArray
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.utils.Utils
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getBoolean
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import org.schabi.newpipe.extractor.utils.orEmptyArray
import org.schabi.newpipe.extractor.utils.orEmptyObject

/**
 * A [CommentsInfoItemExtractor] for YouTube comment data returned in a view model and entity
 * updates.
 */
internal class YoutubeCommentsEUVMInfoItemExtractor(
    private val commentViewModel: JsonObject,
    private val commentRepliesRenderer: JsonObject?,
    private val commentEntityPayload: JsonObject,
    private val engagementToolbarStateEntityPayload: JsonObject,
    private val videoUrl: String,
    private val timeAgoParser: TimeAgoParser
) : CommentsInfoItemExtractor {

    companion object {
        private const val AUTHOR = "author"
        private const val PROPERTIES = "properties"
    }

    @Throws(ParsingException::class)
    override fun getName(): String {
        return getUploaderName()
    }

    @Throws(ParsingException::class)
    override fun getUrl(): String {
        return videoUrl
    }

    @Throws(ParsingException::class)
    override fun getThumbnails(): List<Image> {
        return getUploaderAvatars()
    }

    @Throws(ParsingException::class)
    override fun getLikeCount(): Int {
        val textualLikeCount = getTextualLikeCount()
        try {
            if (Utils.isBlank(textualLikeCount)) {
                return 0
            }
            return Utils.mixedNumberWordToLong(textualLikeCount).toInt()
        } catch (e: Exception) {
            throw ParsingException(
                "Unexpected error while converting textual like count to like count", e
            )
        }
    }

    override fun getTextualLikeCount(): String {
        return commentEntityPayload.getObject("toolbar").orEmptyObject()
            .getString("likeCountNotliked") ?: ""
    }

    @Throws(ParsingException::class)
    override fun getCommentText(): Description {
        // Comments' text work in the same way as an attributed video description
        return Description(
            attributedDescriptionToHtml(
                commentEntityPayload.getObject(PROPERTIES).orEmptyObject()
                    .getObject("content").orEmptyObject()
            ),
            Description.HTML
        )
    }

    @Throws(ParsingException::class)
    override fun getTextualUploadDate(): String {
        return commentEntityPayload.getObject(PROPERTIES).orEmptyObject()
            .getString("publishedTime") ?: ""
    }

    @Throws(ParsingException::class)
    override fun getUploadDate(): DateWrapper? {
        val textualPublishedTime = getTextualUploadDate()
        if (textualPublishedTime.isEmpty()) {
            return null
        }
        return timeAgoParser.parse(textualPublishedTime)
    }

    @Throws(ParsingException::class)
    override fun getCommentId(): String {
        var commentId = commentEntityPayload.getObject(PROPERTIES).orEmptyObject()
            .getString("commentId")
        if (commentId.isNullOrEmpty()) {
            commentId = commentViewModel.getString("commentId")
            if (commentId.isNullOrEmpty()) {
                throw ParsingException("Could not get comment ID")
            }
        }
        return commentId
    }

    @Throws(ParsingException::class)
    override fun getUploaderUrl(): String {
        val author = commentEntityPayload.getObject(AUTHOR).orEmptyObject()
        var channelId = author.getString("channelId")
        if (channelId.isNullOrEmpty()) {
            channelId = author.getObject("channelCommand").orEmptyObject()
                .getObject("innertubeCommand").orEmptyObject()
                .getObject("browseEndpoint").orEmptyObject()
                .getString("browseId")
            if (channelId.isNullOrEmpty()) {
                channelId = author.getObject("avatar").orEmptyObject()
                    .getObject("endpoint").orEmptyObject()
                    .getObject("innertubeCommand").orEmptyObject()
                    .getObject("browseEndpoint").orEmptyObject()
                    .getString("browseId")
                if (channelId.isNullOrEmpty()) {
                    throw ParsingException("Could not get channel ID")
                }
            }
        }
        return "https://www.youtube.com/channel/$channelId"
    }

    @Throws(ParsingException::class)
    override fun getUploaderName(): String {
        return commentEntityPayload.getObject(AUTHOR).orEmptyObject()
            .getString("displayName") ?: ""
    }

    @Throws(ParsingException::class)
    override fun getUploaderAvatars(): List<Image> {
        return getImagesFromThumbnailsArray(
            commentEntityPayload.getObject("avatar").orEmptyObject()
                .getObject("image").orEmptyObject()
                .getArray("sources").orEmptyArray()
        )
    }

    override fun isHeartedByUploader(): Boolean {
        return "TOOLBAR_HEART_STATE_HEARTED" ==
            engagementToolbarStateEntityPayload.getString("heartState")
    }

    override fun isPinned(): Boolean {
        return commentViewModel.containsKey("pinnedText")
    }

    @Throws(ParsingException::class)
    override fun isUploaderVerified(): Boolean {
        val author = commentEntityPayload.getObject(AUTHOR).orEmptyObject()
        return author.getBoolean("isVerified") == true || author.getBoolean("isArtist") == true
    }

    @Throws(ParsingException::class)
    override fun getReplyCount(): Int {
        val replyCountString = commentEntityPayload.getObject("toolbar").orEmptyObject()
            .getString("replyCount")
        if (replyCountString.isNullOrEmpty()) {
            return 0
        }
        return Utils.mixedNumberWordToLong(replyCountString).toInt()
    }

    @Throws(ParsingException::class)
    override fun getReplies(): Page? {
        if (commentRepliesRenderer == null || commentRepliesRenderer.isEmpty()) {
            return null
        }

        val continuation = commentRepliesRenderer.getArray("contents").orEmptyArray()
            .filterIsInstance<JsonObject>()
            .mapNotNull { it.getObject("continuationItemRenderer") }
            .firstOrNull()
            ?.getObject("continuationEndpoint")
            ?.getObject("continuationCommand")
            ?.getString("token")
            ?: throw ParsingException("Could not get comment replies continuation")

        return Page(videoUrl, continuation)
    }

    override fun isChannelOwner(): Boolean {
        return commentEntityPayload.getObject(AUTHOR).orEmptyObject()
            .getBoolean("isCreator") == true
    }

    override fun hasCreatorReply(): Boolean {
        return commentRepliesRenderer != null &&
            commentRepliesRenderer.containsKey("viewRepliesCreatorThumbnail")
    }
}
