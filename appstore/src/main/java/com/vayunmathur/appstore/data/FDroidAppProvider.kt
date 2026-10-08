package com.vayunmathur.appstore.data

import android.content.Context
import com.vayunmathur.library.log.Log
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
        val result = try {
            fetchVerifiedIndex()
        } catch (expected: Exception) {
            Log.status(TAG, "sync of ${descriptor.displayName} failed", expected)
            throw expected
        }
        Log.status(
            TAG,
            "sync of ${descriptor.displayName}: fetched ${result.apps.size} " +
                "apps (signer ${result.signerSha256.take(16)}…)"
        )
        // A verified-but-empty index is a bad publish window, not an empty store: keep the
        // previous rows so one bad sync cannot wipe the catalogue (issue: "catalogue
        // updated — 0 apps" left the Modern Apps section empty until the next good sync).
        if (result.apps.isEmpty()) {
            Log.status(TAG, "sync of ${descriptor.displayName} parsed 0 apps; keeping cached rows")
            return@withContext 0
        }
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

    private companion object {
        const val TAG = "FDroidAppProvider"
    }
}

fun RepoDescriptor.toEntity(): RepoEntity = RepoEntity(
    url = url,
    name = displayName,
    enabled = true,
    fingerprint = pinnedFingerprint,
)
