package com.vayunmathur.email.data

import kotlinx.coroutines.flow.Flow

/** Messages, read status, tombstones and attachments — one concern of [EmailRepository]. */
class EmailMessageStore internal constructor(private val db: EmailDatabase) {
    private val messages: EmailMessageDao get() = db.messageDao()

    fun getMessagesFlow(accountEmail: String, folderName: String, now: Long): Flow<List<EmailMessage>> =
        messages.getMessagesFlow(accountEmail, folderName, now)
    fun getThreadFlow(accountEmail: String, threadId: String): Flow<List<EmailMessage>> =
        messages.getThreadFlow(accountEmail, threadId)
    suspend fun insertMessages(messages: List<EmailMessage>) = this.messages.insertMessages(messages)
    suspend fun getMessage(accountEmail: String, folderName: String, uid: Long): EmailMessage? =
        messages.getMessage(accountEmail, folderName, uid)
    suspend fun deleteMessageRow(accountEmail: String, folderName: String, uid: Long) =
        messages.deleteMessageRow(accountEmail, folderName, uid)
    suspend fun deleteMessageRow(
        accountEmail: String,
        folderName: String,
        uid: Long,
        tombstone: Boolean,
    ) = messages.deleteMessageRow(accountEmail, folderName, uid, tombstone)
    suspend fun insertDeletedUid(tombstone: DeletedUid) = messages.insertDeletedUid(tombstone)
    suspend fun getDeletedUids(accountEmail: String, folderName: String): List<Long> =
        messages.getDeletedUids(accountEmail, folderName)
    suspend fun getKnownUids(accountEmail: String, folderName: String): List<Long> =
        messages.getKnownUids(accountEmail, folderName)
    suspend fun getMessagesWithoutBody(accountEmail: String, limit: Int): List<EmailMessage> =
        messages.getMessagesWithoutBody(accountEmail, limit)
    suspend fun clearMessages(accountEmail: String) = messages.clearMessages(accountEmail)
    suspend fun setSnooze(accountEmail: String, folderName: String, uid: Long, until: Long) =
        messages.setSnooze(accountEmail, folderName, uid, until)
    suspend fun wakeDueSnoozed(now: Long): Int = messages.wakeDueSnoozed(now)
    suspend fun updateReadStatus(accountEmail: String, folderName: String, uid: Long, isRead: Boolean) =
        messages.updateReadStatus(accountEmail, folderName, uid, isRead)
    suspend fun updateBulkReadStatus(accountEmail: String, uids: List<Long>, isRead: Boolean) =
        messages.updateBulkReadStatus(accountEmail, uids, isRead)

    suspend fun insertAttachments(attachments: List<Attachment>) = messages.insertAttachments(attachments)
    suspend fun getAttachments(accountEmail: String, uid: Long): List<Attachment> =
        messages.getAttachments(accountEmail, uid)
    suspend fun updateAttachmentLocalUri(accountEmail: String, uid: Long, partId: String, uri: String) =
        messages.updateAttachmentLocalUri(accountEmail, uid, partId, uri)
}
