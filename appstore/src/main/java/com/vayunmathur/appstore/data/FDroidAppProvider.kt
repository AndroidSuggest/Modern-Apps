package com.vayunmathur.appstore.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FDroidAppProvider(
    private val db: AppDatabase
) : AppProvider {
    override val id = "fdroid"
    override val name = "F-Droid"
    override val source = AppSource.FDROID

    @Volatile var cachedPackageNames: Set<String> = emptySet()

    override suspend fun fetchAll(): List<UnifiedApp> = withContext(Dispatchers.IO) {
        val repos = db.repoDao().all().filter { it.enabled }
        val all = mutableListOf<UnifiedApp>()
        for (repo in repos) {
            try { all += FDroidRepository.fetchRepoIndex(repo.url) } catch (_: Exception) { }
        }
        val filtered = AppProvider.filterTargetSdk(all).distinctBy { it.packageName }
        cachedPackageNames = filtered.map { it.packageName }.toSet()
        filtered
    }

    override suspend fun search(query: String): List<UnifiedApp> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val q = query.lowercase()
        val live = mutableListOf<UnifiedApp>()
        for (url in listOf(DefaultRepos.FDROID_MAIN, DefaultRepos.IZVYZID)) {
            try {
                live += FDroidRepository.fetchRepoIndex(url).filter {
                    it.name.lowercase().contains(q) || it.packageName.lowercase().contains(q) || it.summary.lowercase().contains(q)
                }
            } catch (_: Exception) {}
        }
        AppProvider.filterTargetSdk(live).take(40)
    }

    override suspend fun isPresent(packageName: String): Boolean = withContext(Dispatchers.IO) {
        if (cachedPackageNames.contains(packageName)) return@withContext true
        try {
            val entity = db.cachedAppDao().byPackage(packageName) ?: return@withContext false
            if (entity.targetSdk != null && entity.targetSdk < AppProvider.MIN_TARGET_SDK) return@withContext false
            true
        } catch (_: Exception) { false }
    }

    override suspend fun getDetails(packageName: String): UnifiedApp? = withContext(Dispatchers.IO) {
        try {
            val entity = db.cachedAppDao().byPackage(packageName) ?: return@withContext null
            if (entity.targetSdk != null && entity.targetSdk < AppProvider.MIN_TARGET_SDK) return@withContext null
            entity.toUnifiedApp()
        } catch (_: Exception) { null }
    }

    suspend fun syncIntoDb(): Int = withContext(Dispatchers.IO) {
        val repos = db.repoDao().all().filter { it.enabled }
        var total = 0
        for (repo in repos) {
            try {
                val apps = FDroidRepository.fetchRepoIndex(repo.url)
                val filtered = AppProvider.filterTargetSdk(apps)
                val entities = filtered.map { it.toEntity() }
                db.cachedAppDao().deleteByRepo(repo.url.trimEnd('/'))
                db.cachedAppDao().upsertAll(entities)
                db.repoDao().upsert(repo.copy(lastSync = System.currentTimeMillis()))
                total += entities.size
            } catch (_: Exception) { }
        }
        total
    }

    private fun UnifiedApp.toEntity(): CachedAppEntity = CachedAppEntity(
        packageName = packageName,
        source = source.name,
        name = name,
        summary = summary,
        description = description,
        iconUrl = iconUrl,
        author = author,
        categories = categories.joinToString(","),
        versionName = versionName,
        versionCode = versionCode,
        sizeBytes = sizeBytes,
        apkUrl = apkUrl,
        targetSdk = targetSdk,
        repoUrl = repoUrl?.removeSuffix("/") ?: DefaultRepos.FDROID_MAIN,
        lastUpdated = lastUpdated
    )

    private fun CachedAppEntity.toUnifiedApp(): UnifiedApp = UnifiedApp(
        packageName = packageName,
        source = try { AppSource.valueOf(source) } catch (_: Exception) { AppSource.FDROID },
        name = name,
        summary = summary,
        description = description,
        iconUrl = iconUrl,
        author = author,
        categories = categories.split(",").filter { it.isNotBlank() },
        versionName = versionName,
        versionCode = versionCode,
        sizeBytes = sizeBytes,
        apkUrl = apkUrl,
        targetSdk = targetSdk,
        repoUrl = repoUrl,
        lastUpdated = lastUpdated
    )
}
