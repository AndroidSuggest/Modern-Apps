package org.schabi.newpipe.extractor.comments

import org.schabi.newpipe.extractor.InfoItemsCollector
import org.schabi.newpipe.extractor.exceptions.ParsingException

class CommentsInfoItemsCollector(
    serviceId: Int
) : InfoItemsCollector<CommentsInfoItem, CommentsInfoItemExtractor>(serviceId) {

    @Throws(ParsingException::class)
    override fun extract(extractor: CommentsInfoItemExtractor): CommentsInfoItem {
        val resultItem = CommentsInfoItem(
            getServiceId(), extractor.getUrl(), extractor.getName()
        )

        // optional information
        try {
            resultItem.setCommentId(extractor.getCommentId())
        } catch (e: Exception) {
            addError(e)
        }
        try {
            resultItem.setCommentText(extractor.getCommentText())
        } catch (e: Exception) {
            addError(e)
        }
        try {
            resultItem.setUploaderName(extractor.getUploaderName())
        } catch (e: Exception) {
            addError(e)
        }
        try {
            resultItem.setUploaderAvatars(extractor.getUploaderAvatars())
        } catch (e: Exception) {
            addError(e)
        }
        try {
            resultItem.setUploaderUrl(extractor.getUploaderUrl())
        } catch (e: Exception) {
            addError(e)
        }
        try {
            resultItem.setTextualUploadDate(extractor.getTextualUploadDate())
        } catch (e: Exception) {
            addError(e)
        }
        try {
            resultItem.setUploadDate(extractor.getUploadDate())
        } catch (e: Exception) {
            addError(e)
        }
        try {
            resultItem.setLikeCount(extractor.getLikeCount())
        } catch (e: Exception) {
            addError(e)
        }
        try {
            resultItem.setTextualLikeCount(extractor.getTextualLikeCount())
        } catch (e: Exception) {
            addError(e)
        }
        try {
            resultItem.setThumbnails(extractor.getThumbnails())
        } catch (e: Exception) {
            addError(e)
        }
        try {
            resultItem.setHeartedByUploader(extractor.isHeartedByUploader())
        } catch (e: Exception) {
            addError(e)
        }
        try {
            resultItem.setPinned(extractor.isPinned())
        } catch (e: Exception) {
            addError(e)
        }
        try {
            resultItem.setStreamPosition(extractor.getStreamPosition())
        } catch (e: Exception) {
            addError(e)
        }
        try {
            resultItem.setReplyCount(extractor.getReplyCount())
        } catch (e: Exception) {
            addError(e)
        }
        try {
            resultItem.setReplies(extractor.getReplies())
        } catch (e: Exception) {
            addError(e)
        }
        try {
            resultItem.setChannelOwner(extractor.isChannelOwner())
        } catch (e: Exception) {
            addError(e)
        }
        try {
            resultItem.setCreatorReply(extractor.hasCreatorReply())
        } catch (e: Exception) {
            addError(e)
        }

        return resultItem
    }

    override fun commit(extractor: CommentsInfoItemExtractor) {
        try {
            addItem(extract(extractor))
        } catch (e: Exception) {
            addError(e)
        }
    }

    fun getCommentsInfoItemList(): List<CommentsInfoItem> = ArrayList(getItems())
}
