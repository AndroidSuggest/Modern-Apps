package com.vayunmathur.appstore.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * F-Droid, restricted twice over:
 *
 * 1. **One repository only** — [DefaultRepos.FDROID_MAIN], with its index signing
 *    certificate hard-pinned. Third-party F-Droid-format repos are not supported and
 *    cannot be added, because they ship binaries the upstream developer built.
 * 2. **Reproduced builds only** — a version is offered only if F-Droid's verification
 *    server independently rebuilt it and got identical bytes (see [ReproducibleBuilds]).
 *
 * Both gates fail closed: if the signed index or the verification feed can't be
 * fetched and checked, the sync throws and the previous catalogue is left untouched
 * rather than being replaced with unverified entries.
 *
 * Reads go through [CatalogRepository], which queries the cache table this writes.
 */
class FDroidAppProvider(
    private val db: AppDatabase,
    private val appContext: Context,
) {

    /** Number of packages dropped by the reproducibility gate on the last sync. */
    @Volatile
    var lastFilteredOut: Int = 0
        private set

    /**
     * Refresh from f-droid.org and replace this source's rows. Throws if either the index
     * signature or the reproducibility feed can't be obtained — the caller surfaces that
     * instead of silently serving a stale or unverified catalogue.
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
        // Fetch the reproducibility verdicts first: no verdicts means no catalogue, and
        // there is no point pulling 54 MB of index we would then throw away.
        val verified = ReproducibleBuilds.fetch(appContext)
        if (verified.size == 0) {
            throw java.io.IOException("F-Droid verification feed listed no reproduced builds")
        }
        var rejected = 0
        val result = FDroidRepository.fetchRepoIndex(
            context = appContext,
            repoUrl = DefaultRepos.FDROID_MAIN,
            pinnedFingerprint = FDroidRepository.FDROID_SIGNING_CERT_SHA256,
        ) { pkg, versionCode ->
            val ok = verified.contains(pkg, versionCode)
            if (!ok) rejected++
            ok
        }
        lastFilteredOut = rejected
        return result
    }
}
