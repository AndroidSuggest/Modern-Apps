package com.vayunmathur.email.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

/** Core message CRUD, read status, tombstones and attachments. */
@Dao
interface EmailMessageDao {
    @Query(
        "SELECT * FROM EmailMessage WHERE accountEmail = :accountEmail AND folderName = :folderName " +
            "AND snoozedUntil <= :now ORDER BY dateMillis DESC, id DESC",
    )
    fun getMessagesFlow(
        accountEmail: String,
        folderName: String,
        now: Long,
    ): Flow<List<EmailMessage>>

    @Query(
        "SELECT * FROM EmailMessage WHERE accountEmail = :accountEmail AND threadId = :threadId " +
            "ORDER BY dateMillis ASC, id ASC",
    )
    fun getThreadFlow(accountEmail: String, threadId: String): Flow<List<EmailMessage>>

    /** Raw insert — do not call directly. Use [insertMessages], which populates
     *  [EmailMessage.peekContent] before delegating here. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessagesRaw(messages: List<EmailMessage>)

    /**
     * Insert messages, always (re)computing [EmailMessage.peekContent] from the
     * incoming body via [previewText] so the stored snippet stays in sync with the
     * body regardless of caller. Runs in one transaction.
     */
    @Transaction
    suspend fun insertMessages(messages: List<EmailMessage>) =
        insertMessagesRaw(messages.map { it.copy(peekContent = it.previewText(PEEK_LEN)) })

    @Query(
        "SELECT * FROM EmailMessage WHERE accountEmail = :accountEmail AND id = :uid " +
            "AND folderName = :folderName",
    )
    suspend fun getMessage(accountEmail: String, folderName: String, uid: Long): EmailMessage?

    @Query(
        "DELETE FROM EmailMessage WHERE accountEmail = :accountEmail AND folderName = :folderName " +
            "AND id = :uid",
    )
    suspend fun deleteMessageRow(accountEmail: String, folderName: String, uid: Long)

    // ---- Deleted-UID tombstones ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeletedUid(tombstone: DeletedUid)

    /** UIDs the user deleted locally — consulted by sync to avoid re-inserting them. */
    @Query("SELECT uid FROM DeletedUid WHERE accountEmail = :accountEmail AND folderName = :folderName")
    suspend fun getDeletedUids(accountEmail: String, folderName: String): List<Long>

    /** Record a tombstone and remove the local row in one step. */
    @Transaction
    suspend fun deleteMessageRow(accountEmail: String, folderName: String, uid: Long, tombstone: Boolean) {
        if (tombstone) {
            insertDeletedUid(DeletedUid(accountEmail, folderName, uid))
        }
        deleteMessageRow(accountEmail, folderName, uid)
    }

    /** UIDs already stored for a given folder — used to skip body re-fetch in sync. */
    @Query("SELECT id FROM EmailMessage WHERE accountEmail = :accountEmail AND folderName = :folderName")
    suspend fun getKnownUids(accountEmail: String, folderName: String): List<Long>

    /**
     * Messages we have headers for but no body yet. Used by the sync worker to
     * back-fill bodies in the background once the per-folder header sync is done.
     * Newest first because the user is most likely to open recent mail.
     */
    @Query(
        "SELECT * FROM EmailMessage WHERE accountEmail = :accountEmail AND body IS NULL " +
            "ORDER BY id DESC LIMIT :limit",
    )
    suspend fun getMessagesWithoutBody(accountEmail: String, limit: Int): List<EmailMessage>

    @Query("DELETE FROM EmailMessage WHERE accountEmail = :accountEmail")
    suspend fun clearMessages(accountEmail: String)

    @Query(
        "UPDATE EmailMessage SET snoozedUntil = :until WHERE accountEmail = :accountEmail " +
            "AND folderName = :folderName AND id = :uid",
    )
    suspend fun setSnooze(accountEmail: String, folderName: String, uid: Long, until: Long)

    @Query(
        "UPDATE EmailMessage SET snoozedUntil = 0, isRead = 0 " +
            "WHERE snoozedUntil != 0 AND snoozedUntil <= :now",
    )
    suspend fun wakeDueSnoozed(now: Long): Int

    @Query(
        "UPDATE EmailMessage SET isRead = :isRead WHERE accountEmail = :accountEmail " +
            "AND id = :uid AND folderName = :folderName",
    )
    suspend fun updateReadStatus(
        accountEmail: String,
        folderName: String,
        uid: Long,
        isRead: Boolean,
    )

    @Query("UPDATE EmailMessage SET isRead = :isRead WHERE accountEmail = :accountEmail AND id IN (:uids)")
    suspend fun updateBulkReadStatus(accountEmail: String, uids: List<Long>, isRead: Boolean)

    // Attachments
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachments(attachments: List<Attachment>)

    @Query("SELECT * FROM Attachment WHERE accountEmail = :accountEmail AND messageId = :uid")
    suspend fun getAttachments(accountEmail: String, uid: Long): List<Attachment>

    @Query(
        "UPDATE Attachment SET localUri = :uri WHERE accountEmail = :accountEmail " +
            "AND messageId = :uid AND partId = :partId",
    )
    suspend fun updateAttachmentLocalUri(accountEmail: String, uid: Long, partId: String, uri: String)
}
