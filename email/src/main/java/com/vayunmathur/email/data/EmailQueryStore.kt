package com.vayunmathur.email.data

import kotlinx.coroutines.flow.Flow

/** Search, unified inbox, preview projections and backfills — one concern of [EmailRepository]. */
class EmailQueryStore internal constructor(private val db: EmailDatabase) {
    private val queries: EmailQueryDao get() = db.queryDao()

    fun searchMessagesFlow(
        accountEmail: String,
        folderName: String,
        query: String,
        now: Long,
    ): Flow<List<EmailMessage>> =
        queries.searchMessagesFlow(accountEmail, folderName, query, now)
    fun getUnifiedMessagesFlow(folderName: String, now: Long): Flow<List<EmailMessage>> =
        queries.getUnifiedMessagesFlow(folderName, now)
    fun searchUnifiedMessagesFlow(
        folderName: String,
        query: String,
        now: Long,
    ): Flow<List<EmailMessage>> =
        queries.searchUnifiedMessagesFlow(folderName, query, now)
    suspend fun getRecentUnifiedMessages(): List<EmailMessage> = queries.getRecentUnifiedMessages()
    suspend fun getRecentInboxMessages(): List<EmailMessage> = queries.getRecentInboxMessages()
    suspend fun searchMessages(query: String): List<EmailMessage> = queries.searchMessages(query)
    fun getRecentUnifiedMessagesFlow(): Flow<List<EmailMessage>> = queries.getRecentUnifiedMessagesFlow()
    suspend fun getRowsWithZeroDateMillis(): List<EmailMessage> = queries.getRowsWithZeroDateMillis()
    suspend fun updateDateMillis(accountEmail: String, folderName: String, uid: Long, millis: Long) =
        queries.updateDateMillis(accountEmail, folderName, uid, millis)

    fun getMessagesPreviewFlow(
        accountEmail: String,
        folderName: String,
        now: Long,
    ): Flow<List<EmailPreview>> =
        queries.getMessagesPreviewFlow(accountEmail, folderName, now)
    fun searchMessagesPreviewFlow(
        accountEmail: String,
        folderName: String,
        query: String,
        now: Long,
    ): Flow<List<EmailPreview>> =
        queries.searchMessagesPreviewFlow(accountEmail, folderName, query, now)
    fun getUnifiedMessagesPreviewFlow(folderName: String, now: Long): Flow<List<EmailPreview>> =
        queries.getUnifiedMessagesPreviewFlow(folderName, now)
    fun searchUnifiedMessagesPreviewFlow(
        folderName: String,
        query: String,
        now: Long,
    ): Flow<List<EmailPreview>> =
        queries.searchUnifiedMessagesPreviewFlow(folderName, query, now)
    suspend fun getRecentUnifiedPreview(): List<EmailPreview> = queries.getRecentUnifiedPreview()
    suspend fun getRowsWithEmptyPeek(): List<EmailMessage> = queries.getRowsWithEmptyPeek()
    suspend fun updatePeekContent(accountEmail: String, folderName: String, uid: Long, peek: String) =
        queries.updatePeekContent(accountEmail, folderName, uid, peek)
}
