package com.vayunmathur.appstore.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PlayStoreAppProvider : AppProvider {
    override val id = "playstore"
    override val name = "Play Store"
    override val source = AppSource.PLAYSTORE

    private val knownPackages = mutableSetOf<String>()

    override suspend fun fetchAll(): List<UnifiedApp> = withContext(Dispatchers.IO) {
        val charts = PlayStoreDataSource.topCharts()
        val filtered = AppProvider.filterTargetSdk(charts)
        knownPackages.addAll(filtered.map { it.packageName })
        filtered
    }

    override suspend fun search(query: String): List<UnifiedApp> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val results = PlayStoreDataSource.search(query)
        val filtered = AppProvider.filterTargetSdk(results)
        knownPackages.addAll(filtered.map { it.packageName })
        filtered
    }

    override suspend fun isPresent(packageName: String): Boolean = withContext(Dispatchers.IO) {
        if (knownPackages.contains(packageName)) return@withContext true
        try {
            val details = PlayStoreDataSource.appDetails(packageName) ?: return@withContext false
            if (details.targetSdk != null && details.targetSdk < AppProvider.MIN_TARGET_SDK) return@withContext false
            knownPackages.add(packageName)
            true
        } catch (_: Exception) { false }
    }

    override suspend fun getDetails(packageName: String): UnifiedApp? = withContext(Dispatchers.IO) {
        try {
            val details = PlayStoreDataSource.appDetails(packageName) ?: return@withContext null
            if (details.targetSdk != null && details.targetSdk < AppProvider.MIN_TARGET_SDK) null else details
        } catch (_: Exception) { null }
    }
}
