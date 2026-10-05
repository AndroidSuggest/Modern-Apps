package com.vayunmathur.web.data

import kotlinx.coroutines.flow.Flow

/** Visited-page history behind [HistoryDao]. */
class WebHistoryStore internal constructor(private val dao: HistoryDao) {
    fun allFlow(): Flow<List<HistoryEntry>> = dao.allFlow()
    fun searchFlow(query: String): Flow<List<HistoryEntry>> = dao.searchFlow(query)
    suspend fun upsert(entry: HistoryEntry): Long = dao.upsert(entry)
    suspend fun clearAll() = dao.clearAll()
    suspend fun deleteBefore(before: Long) = dao.deleteBefore(before)
    suspend fun recent(limit: Int): List<HistoryEntry> = dao.recent(limit)
}
