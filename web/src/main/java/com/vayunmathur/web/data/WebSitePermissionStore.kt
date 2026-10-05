package com.vayunmathur.web.data

import kotlinx.coroutines.flow.Flow

/** Per-site permission decisions behind [SitePermissionDao]. */
class WebSitePermissionStore internal constructor(private val dao: SitePermissionDao) {
    fun allFlow(): Flow<List<SitePermission>> = dao.allFlow()
    suspend fun byOrigin(origin: String): SitePermission? = dao.byOrigin(origin)
    fun byOriginFlow(origin: String): Flow<SitePermission?> = dao.byOriginFlow(origin)
    suspend fun upsert(p: SitePermission): Long = dao.upsert(p)
    suspend fun delete(p: SitePermission) = dao.delete(p)
    suspend fun clearAll() = dao.clearAll()
    suspend fun deleteOrigin(origin: String) = dao.deleteOrigin(origin)
}
