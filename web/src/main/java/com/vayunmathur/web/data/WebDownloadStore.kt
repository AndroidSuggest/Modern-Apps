package com.vayunmathur.web.data

import kotlinx.coroutines.flow.Flow

/** Download records behind [DownloadDao]. */
class WebDownloadStore internal constructor(private val dao: DownloadDao) {
    fun allFlow(): Flow<List<DownloadEntry>> = dao.allFlow()
    suspend fun upsert(d: DownloadEntry): Long = dao.upsert(d)
    suspend fun deleteById(id: Long) = dao.deleteById(id)
    suspend fun clearAll() = dao.clearAll()
}
