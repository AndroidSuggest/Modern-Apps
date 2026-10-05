package com.vayunmathur.web.data

import kotlinx.coroutines.flow.Flow

/** Per-site shields overrides behind [ShieldSettingDao]. */
class WebShieldStore internal constructor(private val dao: ShieldSettingDao) {
    fun allFlow(): Flow<List<ShieldSetting>> = dao.allFlow()

    /** One-shot read so the first page load already knows about site exceptions. */
    suspend fun all(): List<ShieldSetting> = dao.all()
    suspend fun byHost(host: String): ShieldSetting? = dao.byHost(host)
    suspend fun upsert(setting: ShieldSetting) = dao.upsert(setting)
    suspend fun deleteHost(host: String) = dao.deleteHost(host)
    suspend fun clearAll() = dao.clearAll()
}
