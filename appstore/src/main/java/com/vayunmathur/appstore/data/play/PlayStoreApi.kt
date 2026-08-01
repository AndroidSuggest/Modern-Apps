package com.vayunmathur.appstore.data.play

import android.content.Context
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.data.models.PlayFile
import com.aurora.gplayapi.helpers.AppDetailsHelper
import com.aurora.gplayapi.helpers.PurchaseHelper
import com.aurora.gplayapi.helpers.SearchHelper
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.security.ApkCertificates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wrapper around gplayapi helpers for Play Store operations.
 * Uses AuthData + IHttpClient (PlayHttpClient).
 */
class PlayStoreApi(
    private val authData: AuthData,
    private val httpClient: PlayHttpClient
) {

    suspend fun getDetails(packageName: String): UnifiedApp? = withContext(Dispatchers.IO) {
        try {
            val helper = AppDetailsHelper(authData).using(httpClient)
            val app = helper.getAppByPackageName(packageName)

            UnifiedApp(
                packageName = app.packageName,
                source = AppSource.PLAYSTORE,
                name = app.displayName.takeIf { it.isNotBlank() } ?: packageName.substringAfterLast('.'),
                summary = app.shortDescription,
                description = app.description.takeIf { it.isNotBlank() } ?: app.shortDescription,
                iconUrl = app.iconArtwork.url.takeIf { it.isNotBlank() },
                author = app.developerName,
                categories = emptyList(),
                versionName = app.versionName,
                versionCode = app.versionCode,
                sizeBytes = app.size,
                website = "https://play.google.com/store/apps/details?id=$packageName",
                offerType = app.offerType,
                isFree = app.isFree,
                whatsNew = app.changes.takeIf { it.isNotBlank() },
                targetSdk = app.targetSdk,
                // AppDetails.certificateSet[].sha256 is the signing certificate Google
                // says this package should carry — the same expectation the Play client
                // stores on its library entry and compares against PackageManager. It
                // pins the bytes against a swapped CDN response, but note that Google
                // supplies both this value and the APK, so it is not a publisher key.
                expectedSigners = app.certificateSetList
                    .mapNotNull { it.sha256?.let(ApkCertificates::normalizeFingerprint) }
                    .distinct(),
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun search(query: String): List<UnifiedApp> = withContext(Dispatchers.IO) {
        try {
            val helper = SearchHelper(authData).using(httpClient)
            val bundle = helper.searchResults(query)
            val allApps = bundle.streamClusters.values.flatMap { it.clusterAppList }
            allApps.mapNotNull { app ->
                try {
                    UnifiedApp(
                        packageName = app.packageName,
                        source = AppSource.PLAYSTORE,
                        name = app.displayName.takeIf { it.isNotBlank() } ?: app.packageName.substringAfterLast('.'),
                        summary = app.shortDescription,
                        description = app.shortDescription,
                        iconUrl = app.iconArtwork.url.takeIf { it.isNotBlank() },
                        author = app.developerName,
                        versionName = app.versionName,
                        versionCode = app.versionCode,
                        website = "https://play.google.com/store/apps/details?id=${app.packageName}",
                        offerType = app.offerType
                    )
                } catch (_: Exception) { null }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Purchase flow - returns list of PlayFile.
     * Throws on failure.
     */
    suspend fun purchase(
        context: Context,
        packageName: String,
        versionCode: Long,
        offerType: Int = 0,
        certHash: String? = null
    ): List<PlayFile> = withContext(Dispatchers.IO) {
        val purchaseHelper = PurchaseHelper(authData).using(httpClient)
        purchaseHelper.purchase(packageName, versionCode, offerType, certHash)
    }
}
