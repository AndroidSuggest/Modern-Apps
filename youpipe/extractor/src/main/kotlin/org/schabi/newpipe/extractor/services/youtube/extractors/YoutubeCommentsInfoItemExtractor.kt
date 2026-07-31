package org.schabi.newpipe.extractor.services.youtube.extractors

import kotlinx.serialization.json.JsonObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.comments.CommentsInfoItemExtractor
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.localization.TimeAgoParser
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getImagesFromThumbnailsArray
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextFromObject
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.utils.JsonUtils
import org.schabi.newpipe.extractor.utils.Utils
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getBoolean
import org.schabi.newpipe.extractor.utils.getInt
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString

class YoutubeCommentsInfoItemExtractor(
    private val commentRenderer: JsonObject,
    private val commentRepliesRenderer: JsonObject?,
    private val url: String,
    private val timeAgoParser: TimeAgoParser
) : CommentsInfoItemExtractor {

    @Throws(ParsingException::class)
    private fun getAuthorThumbnails(): List<Image> {
        try {
            return getImagesFromThumbnailsArray(
                JsonUtils.getArray(commentRenderer, "authorThumbnail.thumbnails")
            )
        } catch (e: Exception) {
            throw ParsingException("Could not get author thumbnails", e)
        }
    }

    override fun getUrl(): String = url

    @Throws(ParsingException::class)
    override fun getThumbnails(): List<Image> = getAuthorThumbnails()

    override fun getName(): String {
        try {
            return getTextFromObject(JsonUtils.getObject(commentRenderer, "authorText")) ?: ""
        } catch (e: Exception) {
            return ""
        }
    }

    @Throws(ParsingException::class)
    override fun getTextualUploadDate(): String {
        try {
            return getTextFromObject(
                JsonUtils.getObject(commentRenderer, "publishedTimeText")
            ) ?: throw ParsingException("Could not get publishedTimeText")
        } catch (e: ParsingException) {
            throw e
        } catch (e: Exception) {
            throw ParsingException("Could not get publishedTimeText", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getUploadDate(): DateWrapper? {
        val textualPublishedTime = getTextualUploadDate()
        return if (textualPublishedTime.isNotEmpty()) {
            timeAgoParser.parse(textualPublishedTime)
        } else {
            null
        }
    }

    /**
     * The method tries first to get the exact like count by using the accessibility data
     * returned. But if the parsing of this accessibility data fails, the method parses internally
     * a localized string.
     */
    @Throws(ParsingException::class)
    override fun getLikeCount(): Int {
        val likeCount: String
        try {
            likeCount = Utils.removeNonDigitCharacters(
                JsonUtils.getString(
                    commentRenderer,
                    "actionButtons.commentActionButtonsRenderer.likeButton.toggleButtonRenderer" +
                        ".accessibilityData.accessibilityData.label"
                )
            )
        } catch (e: Exception) {
            val textualLikeCount = getTextualLikeCount()
            try {
                if (Utils.isBlank(textualLikeCount)) {
                    return 0
                }
                return Utils.mixedNumberWordToLong(textualLikeCount).toInt()
            } catch (i: Exception) {
                throw ParsingException(
                    "Unexpected error while converting textual like count to like count", i
                )
            }
        }

        try {
            if (Utils.isBlank(likeCount)) {
                return 0
            }
            return likeCount.toInt()
        } catch (e: Exception) {
            throw ParsingException("Unexpected error while parsing like count as Integer", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getTextualLikeCount(): String {
        try {
            if (!commentRenderer.containsKey("voteCount")) {
                return ""
            }

            val voteCountObj = JsonUtils.getObject(commentRenderer, "voteCount")
            if (voteCountObj.isEmpty()) {
                return ""
            }
            return getTextFromObject(voteCountObj) ?: ""
        } catch (e: Exception) {
            throw ParsingException("Could not get the vote count", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getCommentText(): Description {
        try {
            val contentText = JsonUtils.getObject(commentRenderer, "contentText")
            if (contentText.isEmpty()) {
                return Description.EMPTY_DESCRIPTION
            }
            val commentText = getTextFromObject(contentText, true) ?: ""
            // YouTube adds U+FEFF in some comments.
            val commentTextBomRemoved = Utils.removeUTF8BOM(commentText)

            return Description(commentTextBomRemoved, Description.HTML)
        } catch (e: Exception) {
            throw ParsingException("Could not get comment text", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getCommentId(): String {
        try {
            return JsonUtils.getString(commentRenderer, "commentId")
        } catch (e: Exception) {
            throw ParsingException("Could not get comment id", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getUploaderAvatars(): List<Image> = getAuthorThumbnails()

    override fun isHeartedByUploader(): Boolean {
        val commentActionButtonsRenderer = commentRenderer.getObject("actionButtons")
            ?.getObject("commentActionButtonsRenderer")
        return commentActionButtonsRenderer?.containsKey("creatorHeart") == true
    }

    override fun isPinned(): Boolean = commentRenderer.containsKey("pinnedCommentBadge")

    @Throws(ParsingException::class)
    override fun isUploaderVerified(): Boolean = commentRenderer.containsKey("authorCommentBadge")

    @Throws(ParsingException::class)
    override fun getUploaderName(): String {
        try {
            return getTextFromObject(JsonUtils.getObject(commentRenderer, "authorText")) ?: ""
        } catch (e: Exception) {
            return ""
        }
    }

    @Throws(ParsingException::class)
    override fun getUploaderUrl(): String {
        try {
            return "https://www.youtube.com/channel/" + JsonUtils.getString(
                commentRenderer, "authorEndpoint.browseEndpoint.browseId"
            )
        } catch (e: Exception) {
            return ""
        }
    }

    override fun getReplyCount(): Int {
        if (commentRenderer.containsKey("replyCount")) {
            return commentRenderer.getInt("replyCount") ?: CommentsInfoItem.UNKNOWN_REPLY_COUNT
        }
        return CommentsInfoItem.UNKNOWN_REPLY_COUNT
    }

    override fun getReplies(): Page? {
        if (commentRepliesRenderer == null) {
            return null
        }

        try {
            val id = JsonUtils.getString(
                JsonUtils.getArray(commentRepliesRenderer, "contents")
                    .getObject(0)!!,
                "continuationItemRenderer.continuationEndpoint.continuationCommand.token"
            )
            return Page(url, id)
        } catch (e: Exception) {
            return null
        }
    }

    override fun isChannelOwner(): Boolean {
        return commentRenderer.getBoolean("authorIsChannelOwner") == true
    }

    override fun hasCreatorReply(): Boolean {
        if (commentRepliesRenderer == null) {
            return false
        }
        return commentRepliesRenderer.containsKey("viewRepliesCreatorThumbnail")
    }
}
