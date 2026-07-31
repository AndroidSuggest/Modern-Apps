package org.schabi.newpipe.extractor.comments

import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItemExtractor
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamExtractor
import javax.annotation.Nonnull
import javax.annotation.Nullable

interface CommentsInfoItemExtractor : InfoItemExtractor {

    /**
     * Return the like count of the comment,
     * or [CommentsInfoItem.NO_LIKE_COUNT] if it is unavailable.
     *
     * NOTE: Currently only implemented for YT with limitations (only approximate like count is returned)
     *
     * @return the comment's like count or [CommentsInfoItem.NO_LIKE_COUNT] if it is unavailable
     * @see StreamExtractor.getLikeCount
     */
    @Throws(ParsingException::class)
    fun getLikeCount(): Int = CommentsInfoItem.NO_LIKE_COUNT

    /**
     * The unmodified like count given by the service
     * It may be language dependent
     */
    @Throws(ParsingException::class)
    fun getTextualLikeCount(): String = ""

    /**
     * The text of the comment
     */
    @Nonnull
    @Throws(ParsingException::class)
    fun getCommentText(): Description = Description.EMPTY_DESCRIPTION

    /**
     * The upload date given by the service, unmodified
     *
     * @see StreamExtractor.getTextualUploadDate
     */
    @Throws(ParsingException::class)
    fun getTextualUploadDate(): String = ""

    /**
     * The upload date wrapped with DateWrapper class
     *
     * @see StreamExtractor.getUploadDate
     */
    @Nullable
    @Throws(ParsingException::class)
    fun getUploadDate(): DateWrapper? = null

    @Throws(ParsingException::class)
    fun getCommentId(): String = ""

    @Throws(ParsingException::class)
    fun getUploaderUrl(): String = ""

    @Throws(ParsingException::class)
    fun getUploaderName(): String = ""

    @Nonnull
    @Throws(ParsingException::class)
    fun getUploaderAvatars(): List<Image> = emptyList()

    /**
     * Whether the comment has been hearted by the uploader
     */
    @Throws(ParsingException::class)
    fun isHeartedByUploader(): Boolean = false

    /**
     * Whether the comment is pinned
     */
    @Throws(ParsingException::class)
    fun isPinned(): Boolean = false

    /**
     * Whether the uploader is verified by the service
     */
    @Throws(ParsingException::class)
    fun isUploaderVerified(): Boolean = false

    /**
     * The playback position of the stream to which this comment belongs.
     *
     * @see CommentsInfoItem.getStreamPosition
     */
    @Throws(ParsingException::class)
    fun getStreamPosition(): Int = CommentsInfoItem.NO_STREAM_POSITION

    /**
     * The count of comment replies.
     *
     * @return the count of the replies or [CommentsInfoItem.UNKNOWN_REPLY_COUNT] if replies are not supported
     */
    @Throws(ParsingException::class)
    fun getReplyCount(): Int = CommentsInfoItem.UNKNOWN_REPLY_COUNT

    /**
     * The continuation page which is used to get comment replies from.
     *
     * @return the continuation Page for the replies, or null if replies are not supported
     */
    @Nullable
    @Throws(ParsingException::class)
    fun getReplies(): Page? = null

    /**
     * Whether the comment was made by the channel owner.
     */
    @Throws(ParsingException::class)
    fun isChannelOwner(): Boolean = false

    /**
     * Whether the comment was replied to by the creator.
     */
    @Throws(ParsingException::class)
    fun hasCreatorReply(): Boolean = false
}
