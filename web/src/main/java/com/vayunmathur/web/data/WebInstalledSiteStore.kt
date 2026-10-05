package com.vayunmathur.web.data

import kotlinx.coroutines.flow.Flow

/** Installed (pinned) sites behind [InstalledSiteDao]. */
class WebInstalledSiteStore internal constructor(private val dao: InstalledSiteDao) {
    fun allFlow(): Flow<List<InstalledSite>> = dao.allFlow()
    suspend fun byId(id: String): InstalledSite? = dao.byId(id)
    suspend fun byOrigin(origin: String): InstalledSite? = dao.byOrigin(origin)
    suspend fun upsert(site: InstalledSite) = dao.upsert(site)
    suspend fun deleteById(id: String) = dao.deleteById(id)
    suspend fun clearAll() = dao.clearAll()
}
