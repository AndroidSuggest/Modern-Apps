package com.vayunmathur.email.data

import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/** Full-text search, unified-inbox and preview projections, plus one-time backfills. */
@Dao
interface EmailQueryDao {
    @Query(
        "SELECT * FROM EmailMessage WHERE accountEmail = :accountEmail AND folderName = :folderName " +
            "AND snoozedUntil <= :now AND (subject LIKE '%' || :query || '%' OR `from` LIKE '%' || :query || '%' " +
            "OR body LIKE '%' || :query || '%') ORDER BY dateMillis DESC, id DESC",
    )
    fun searchMessagesFlow(
        accountEmail: String,
        folderName: String,
        query: String,
        now: Long,
    ): Flow<List<EmailMessage>>

    // Unified Inbox
    @Query(
        "SELECT * FROM EmailMessage WHERE folderName = :folderName AND snoozedUntil <= :now " +
            "ORDER BY dateMillis DESC, id DESC",
    )
    fun getUnifiedMessagesFlow(folderName: String, now: Long): Flow<List<EmailMessage>>

    @Query(
        "SELECT * FROM EmailMessage WHERE folderName = :folderName AND snoozedUntil <= :now " +
            "AND (subject LIKE '%' || :query || '%' OR `from` LIKE '%' || :query || '%' " +
            "OR body LIKE '%' || :query || '%') ORDER BY dateMillis DESC, id DESC",
    )
    fun searchUnifiedMessagesFlow(
        folderName: String,
        query: String,
        now: Long,
    ): Flow<List<EmailMessage>>

    @Query("SELECT * FROM EmailMessage WHERE folderName = 'INBOX' ORDER BY dateMillis DESC, id DESC LIMIT 100")
    suspend fun getRecentUnifiedMessages(): List<EmailMessage>

    @Query("SELECT * FROM EmailMessage WHERE folderName = 'INBOX' ORDER BY dateMillis DESC, id DESC LIMIT 30")
    suspend fun getRecentInboxMessages(): List<EmailMessage>

    @Query(
        "SELECT * FROM EmailMessage WHERE subject LIKE '%' || :query || '%' " +
            "OR `from` LIKE '%' || :query || '%' OR body LIKE '%' || :query || '%' " +
            "ORDER BY dateMillis DESC, id DESC LIMIT 30",
    )
    suspend fun searchMessages(query: String): List<EmailMessage>

    @Query("SELECT * FROM EmailMessage WHERE folderName = 'INBOX' ORDER BY dateMillis DESC, id DESC LIMIT 10")
    fun getRecentUnifiedMessagesFlow(): Flow<List<EmailMessage>>

    // ---- Light preview projections (no body) for list/widget screens ----
    // These SELECT only the columns [EmailPreview] needs and read the stored
    // peekContent snippet, so list rows and the widget never load the full body.

    @Query(
        "SELECT accountEmail, folderName, id, threadId, subject, `from`, date, dateMillis, isRead, " +
            "hasAttachments, snoozedUntil, peekContent FROM EmailMessage " +
            "WHERE accountEmail = :accountEmail AND folderName = :folderName AND snoozedUntil <= :now " +
            "ORDER BY dateMillis DESC, id DESC",
    )
    fun getMessagesPreviewFlow(
        accountEmail: String,
        folderName: String,
        now: Long,
    ): Flow<List<EmailPreview>>

    @Query(
        "SELECT accountEmail, folderName, id, threadId, subject, `from`, date, dateMillis, isRead, " +
            "hasAttachments, snoozedUntil, peekContent FROM EmailMessage " +
            "WHERE accountEmail = :accountEmail AND folderName = :folderName AND snoozedUntil <= :now " +
            "AND (subject LIKE '%' || :query || '%' OR `from` LIKE '%' || :query || '%' " +
            "OR body LIKE '%' || :query || '%') ORDER BY dateMillis DESC, id DESC",
    )
    fun searchMessagesPreviewFlow(
        accountEmail: String,
        folderName: String,
        query: String,
        now: Long,
    ): Flow<List<EmailPreview>>

    @Query(
        "SELECT accountEmail, folderName, id, threadId, subject, `from`, date, dateMillis, isRead, " +
            "hasAttachments, snoozedUntil, peekContent FROM EmailMessage " +
            "WHERE folderName = :folderName AND snoozedUntil <= :now ORDER BY dateMillis DESC, id DESC",
    )
    fun getUnifiedMessagesPreviewFlow(folderName: String, now: Long): Flow<List<EmailPreview>>

    @Query(
        "SELECT accountEmail, folderName, id, threadId, subject, `from`, date, dateMillis, isRead, " +
            "hasAttachments, snoozedUntil, peekContent FROM EmailMessage " +
            "WHERE folderName = :folderName AND snoozedUntil <= :now " +
            "AND (subject LIKE '%' || :query || '%' OR `from` LIKE '%' || :query || '%' " +
            "OR body LIKE '%' || :query || '%') ORDER BY dateMillis DESC, id DESC",
    )
    fun searchUnifiedMessagesPreviewFlow(
        folderName: String,
        query: String,
        now: Long,
    ): Flow<List<EmailPreview>>

    @Query(
        "SELECT accountEmail, folderName, id, threadId, subject, `from`, date, dateMillis, isRead, " +
            "hasAttachments, snoozedUntil, peekContent FROM EmailMessage WHERE folderName = 'INBOX' " +
            "ORDER BY dateMillis DESC, id DESC LIMIT 100",
    )
    suspend fun getRecentUnifiedPreview(): List<EmailPreview>

    // ---- One-time backfill for the dateMillis column ----

    /** Rows persisted before `dateMillis` existed; their `date` string needs parsing. */
    @Query("SELECT * FROM EmailMessage WHERE dateMillis = 0 LIMIT 1000")
    suspend fun getRowsWithZeroDateMillis(): List<EmailMessage>

    @Query(
        "UPDATE EmailMessage SET dateMillis = :millis WHERE accountEmail = :accountEmail " +
            "AND folderName = :folderName AND id = :uid",
    )
    suspend fun updateDateMillis(
        accountEmail: String,
        folderName: String,
        uid: Long,
        millis: Long,
    )

    // ---- One-time backfill for the peekContent column ----

    /** Rows with a body but no computed preview yet — either persisted before the
     *  peekContent column existed (default '') or inserted with an empty body since
     *  filled in. previewText's HTML strip can't be done in SQL, so recompute in
     *  Kotlin (see [PeekContentBackfill]). */
    @Query("SELECT * FROM EmailMessage WHERE peekContent = '' AND body IS NOT NULL AND body != '' LIMIT 1000")
    suspend fun getRowsWithEmptyPeek(): List<EmailMessage>

    @Query(
        "UPDATE EmailMessage SET peekContent = :peek WHERE accountEmail = :accountEmail " +
            "AND folderName = :folderName AND id = :uid",
    )
    suspend fun updatePeekContent(accountEmail: String, folderName: String, uid: Long, peek: String)
}
