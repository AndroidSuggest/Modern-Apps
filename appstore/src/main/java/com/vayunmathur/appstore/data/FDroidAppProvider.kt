package com.vayunmathur.appstore.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * F-Droid, from a single hard-pinned repository.
 *
 * **One repository only** — [DefaultRepos.FDROID_MAIN], with its index signing certificate
 * hard-pinned. Third-party F-Droid-format repos are not supported and cannot be added,
 * because they ship binaries the upstream developer built.
 *
 * The whole catalogue is imported (newest version of every app). F-Droid's reproducibility
 * feed ([ReproducibleBuilds]) is consulted only to *badge* the versions that were
 * independently reproduced bit-for-bit — it no longer decides what is listed. That feed is
 * best-effort: if it can't be fetched, apps are simply imported without the badge rather
 * than the sync failing. The signed-index gate still fails closed, so the catalogue is
 * never replaced with entries whose hashes and signer keys weren't authenticated.
 *
 * Reads go through [CatalogRepository], which queries the cache table this writes.
 */
class FDroidAppProvider(
    private val db: AppDatabase,
    private val appContext: Context,
) {

    /** Number of packages tagged reproducible on the last sync. */
    @Volatile
    var lastReproducibleCount: Int = 0
        private set

    /**
     * Refresh from f-droid.org and replace this source's rows. Throws if the signed index
     * can't be obtained — the caller surfaces that instead of silently serving a stale or
     * unauthenticated catalogue. A missing reproducibility feed is not fatal.
     */
    suspend fun syncIntoDb(): Int = withContext(Dispatchers.IO) {
        val result = fetchVerifiedIndex()
        val repo = db.repoDao().all().find { it.url == DefaultRepos.FDROID_MAIN }
        db.cachedAppDao().deleteByRepo(DefaultRepos.FDROID_MAIN)
        db.cachedAppDao().upsertAll(result.apps.map { it.toEntity() })
        db.repoDao().upsert(
            (repo ?: RepoEntity(DefaultRepos.FDROID_MAIN, "F-Droid", true)).copy(
                fingerprint = result.signerSha256,
                lastSync = System.currentTimeMillis(),
            )
        )
        result.apps.size
    }

    private suspend fun fetchVerifiedIndex(): FDroidRepository.IndexResult {
        // Reproducibility verdicts are best-effort: a fetch failure just means nothing gets
        // the badge this sync, not that the catalogue disappears.
        val verified = runCatching { ReproducibleBuilds.fetch(appContext) }.getOrNull()
        var reproduced = 0
        val result = FDroidRepository.fetchRepoIndex(
            context = appContext,
            repoUrl = DefaultRepos.FDROID_MAIN,
            pinnedFingerprint = FDroidRepository.FDROID_SIGNING_CERT_SHA256,
        ) { pkg, versionCode ->
            val ok = verified?.contains(pkg, versionCode) == true
            if (ok) reproduced++
            ok
        }
        lastReproducibleCount = reproduced
        return result
    }
}
