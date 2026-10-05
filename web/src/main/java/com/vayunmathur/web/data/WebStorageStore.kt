package com.vayunmathur.web.data

import kotlinx.coroutines.flow.Flow

/** Observed site storage footprints behind [StorageInfoDao]. */
class WebStorageStore internal constructor(private val dao: StorageInfoDao) {
    fun allFlow(): Flow<List<StorageInfo>> = dao.allFlow()
    suspend fun byOrigin(origin: String): StorageInfo? = dao.byOrigin(origin)
    suspend fun upsert(info: StorageInfo): Long = dao.upsert(info)
    suspend fun delete(info: StorageInfo) = dao.delete(info)
    suspend fun deleteOrigin(origin: String) = dao.deleteOrigin(origin)
    suspend fun clearAll() = dao.clearAll()
}
