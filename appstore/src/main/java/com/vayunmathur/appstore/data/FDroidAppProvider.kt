package com.vayunmathur.appstore.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Imports one hard-pinned F-Droid-format repository into the shared offline catalogue.
 *
 * The whole catalogue is imported (newest version of every app). For repositories that
 * support it, F-Droid's reproducibility feed ([ReproducibleBuilds]) is consulted only to
 * badge versions independently reproduced bit-for-bit. That feed is best-effort; signed-index
 * verification always fails closed, so unauthenticated hashes and signer keys never replace
 * cached rows.
 */
class FDroidAppProvider(
    private val db: AppDatabase,
    private val appContext: Context,
    private val descriptor: RepoDescriptor,
) {

    /** Number of packages tagged reproducible on the last sync. */
    @Volatile
    var lastReproducibleCount: Int = 0
        private set

    /** Refresh [descriptor] and replace only that repository's cached rows. */
    suspend fun syncIntoDb(): Int = withContext(Dispatchers.IO) {
        val result = fetchVerifiedIndex()
        val repo = db.repoDao().all().find { it.url == descriptor.url }
        db.cachedAppDao().deleteByRepo(descriptor.url)
        db.cachedAppDao().upsertAll(result.apps.map { it.toEntity() })
        db.repoDao().upsert(
            (repo ?: descriptor.toEntity()).copy(
                fingerprint = result.signerSha256,
                lastSync = System.currentTimeMillis(),
            )
        )
        result.apps.size
    }

    private suspend fun fetchVerifiedIndex(): FDroidRepository.IndexResult {
        val verified = if (descriptor.supportsReproducibilityFeed) {
            runCatching { ReproducibleBuilds.fetch(appContext) }.getOrNull()
        } else {
            null
        }
        var reproduced = 0
        val result = FDroidRepository.fetchRepoIndex(
            context = appContext,
            repoUrl = descriptor.url,
            pinnedFingerprint = descriptor.pinnedFingerprint,
            source = descriptor.source,
        ) { pkg, versionCode ->
            val ok = descriptor.supportsReproducibilityFeed &&
                verified?.contains(pkg, versionCode) == true
            if (ok) reproduced++
            ok
        }
        lastReproducibleCount = reproduced
        return result
    }
}

fun RepoDescriptor.toEntity(): RepoEntity = RepoEntity(
    url = url,
    name = displayName,
    enabled = true,
    fingerprint = pinnedFingerprint,
)
