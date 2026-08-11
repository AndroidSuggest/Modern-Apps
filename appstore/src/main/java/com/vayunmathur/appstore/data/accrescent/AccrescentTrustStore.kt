package com.vayunmathur.appstore.data.accrescent

import com.vayunmathur.appstore.data.AccrescentTrustEntity
import com.vayunmathur.appstore.data.AccrescentTrustDao

/**
 * Room-cached view of Accrescent's verified allowlist.
 *
 * Every read here answers a trust question — "what certificate must this app id be signed
 * with?" and "what is the oldest version we may install?" — and the answers only ever come
 * from a [RepoData] that [AccrescentRepoDataFetcher] has already ed25519-verified. Nothing
 * writes to this store except [replaceFrom].
 */
class AccrescentTrustStore(private val dao: AccrescentTrustDao) {

    /** Overwrite the whole allowlist from a freshly verified repodata document. */
    suspend fun replaceFrom(repoData: RepoData) {
        dao.replaceAll(
            repoData.apps.map { (appId, app) ->
                AccrescentTrustEntity(
                    appId = appId,
                    signingCertHash = app.signingCertHash.lowercase(),
                    minVersionCode = app.minVersionCode,
                )
            }
        )
    }

    /** The certificate fingerprint the app's APK must be signed with, or null if not listed. */
    suspend fun signerFor(appId: String): String? = dao.byId(appId)?.signingCertHash

    /** The minimum installable version code for [appId], or null if not listed. */
    suspend fun minVersionFor(appId: String): Long? = dao.byId(appId)?.minVersionCode

    /** Both trust values in one lookup, or null when [appId] is not in the allowlist. */
    suspend fun entryFor(appId: String): AccrescentTrustEntity? = dao.byId(appId)

    /** Every app id Accrescent's signed list vouches for, for attribution + membership checks. */
    suspend fun appIds(): Set<String> = dao.allIds().toSet()
}
