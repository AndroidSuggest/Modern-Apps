package org.schabi.newpipe.extractor.comments

import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.stream.Description
import javax.annotation.Nonnull
import javax.annotation.Nullable

open class CommentsInfoItem(
    serviceId: Int,
    url: String,
    name: String
) : InfoItem(InfoType.COMMENT, serviceId, url, name) {

    companion object {
        const val NO_LIKE_COUNT = -1
        const val NO_STREAM_POSITION = -1
        const val UNKNOWN_REPLY_COUNT = -1
    }

    private var commentId: String? = null

    @Nonnull
    private var commentText: Description = Description.EMPTY_DESCRIPTION

    private var uploaderName: String? = null

    @Nonnull
    private var uploaderAvatars: List<Image> = emptyList()

    private var uploaderUrl: String? = null
    private var uploaderVerified: Boolean = false
    private var textualUploadDate: String? = null

    @Nullable
    private var uploadDate: DateWrapper? = null

    private var likeCount: Int = 0
    private var textualLikeCount: String? = null
    private var heartedByUploader: Boolean = false
    private var pinned: Boolean = false
    private var streamPosition: Int = 0
    private var replyCount: Int = 0

    @Nullable
    private var replies: Page? = null

    private var isChannelOwner: Boolean = false
    private var creatorReply: Boolean = false

    fun getCommentId(): String? = commentId
    fun setCommentId(commentId: String?) {
        this.commentId = commentId
    }

    @Nonnull
    fun getCommentText(): Description = commentText
    fun setCommentText(commentText: Description) {
        this.commentText = commentText
    }

    fun getUploaderName(): String? = uploaderName
    fun setUploaderName(uploaderName: String?) {
        this.uploaderName = uploaderName
    }

    @Nonnull
    fun getUploaderAvatars(): List<Image> = uploaderAvatars
    fun setUploaderAvatars(uploaderAvatars: List<Image>) {
        this.uploaderAvatars = uploaderAvatars
    }

    fun getUploaderUrl(): String? = uploaderUrl
    fun setUploaderUrl(uploaderUrl: String?) {
        this.uploaderUrl = uploaderUrl
    }

    fun getTextualUploadDate(): String? = textualUploadDate
    fun setTextualUploadDate(textualUploadDate: String?) {
        this.textualUploadDate = textualUploadDate
    }

    @Nullable
    fun getUploadDate(): DateWrapper? = uploadDate
    fun setUploadDate(uploadDate: DateWrapper?) {
        this.uploadDate = uploadDate
    }

    /**
     * @return the comment's like count or [NO_LIKE_COUNT] if it is unavailable
     */
    fun getLikeCount(): Int = likeCount
    fun setLikeCount(likeCount: Int) {
        this.likeCount = likeCount
    }

    fun getTextualLikeCount(): String? = textualLikeCount
    fun setTextualLikeCount(textualLikeCount: String?) {
        this.textualLikeCount = textualLikeCount
    }

    fun setHeartedByUploader(isHeartedByUploader: Boolean) {
        this.heartedByUploader = isHeartedByUploader
    }

    fun isHeartedByUploader(): Boolean = heartedByUploader

    fun isPinned(): Boolean = pinned
    fun setPinned(pinned: Boolean) {
        this.pinned = pinned
    }

    fun setUploaderVerified(uploaderVerified: Boolean) {
        this.uploaderVerified = uploaderVerified
    }

    fun isUploaderVerified(): Boolean = uploaderVerified

    fun setStreamPosition(streamPosition: Int) {
        this.streamPosition = streamPosition
    }

    /**
     * Get the playback position of the stream to which this comment belongs.
     * This is not supported by all services.
     *
     * @return the playback position in seconds or [NO_STREAM_POSITION] if not available
     */
    fun getStreamPosition(): Int = streamPosition

    fun setReplyCount(replyCount: Int) {
        this.replyCount = replyCount
    }

    fun getReplyCount(): Int = replyCount

    fun setReplies(replies: Page?) {
        this.replies = replies
    }

    @Nullable
    fun getReplies(): Page? = replies

    fun setChannelOwner(channelOwner: Boolean) {
        this.isChannelOwner = channelOwner
    }

    fun isChannelOwner(): Boolean = isChannelOwner

    fun setCreatorReply(creatorReply: Boolean) {
        this.creatorReply = creatorReply
    }

    fun hasCreatorReply(): Boolean = creatorReply
}
