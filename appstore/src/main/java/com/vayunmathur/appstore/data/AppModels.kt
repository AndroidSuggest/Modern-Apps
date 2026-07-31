package com.vayunmathur.appstore.data

import kotlinx.serialization.Serializable

enum class AppSource {
    FDROID,
    PLAYSTORE
}

@Serializable
data class UnifiedApp(
    val packageName: String,
    val source: AppSource,
    val name: String,
    val summary: String = "",
    val description: String = "",
    val iconUrl: String? = null,
    val author: String? = null,
    val categories: List<String> = emptyList(),
    val versionName: String? = null,
    val versionCode: Long = 0L,
    val sizeBytes: Long = 0L,
    val apkUrl: String? = null,
    val screenshotUrls: List<String> = emptyList(),
    val rating: Float? = null,
    val license: String? = null,
    val website: String? = null,
    val sourceCode: String? = null,
    val whatsNew: String? = null,
    val addedTimestamp: Long = 0L,
    val lastUpdated: Long = 0L,
    val antiFeatures: List<String> = emptyList(),
    val isFree: Boolean = true,
    val repoUrl: String? = null
)

data class InstalledInfo(
    val packageName: String,
    val name: String,
    val versionName: String?,
    val versionCode: Long,
    val isSystem: Boolean = false
)

object DefaultRepos {
    const val FDROID_MAIN = "https://f-droid.org/repo"
    const val FDROID_ARCHIVE = "https://f-droid.org/archive"
    const val IZVYZID = "https://apt.izzysoft.de/fdroid/repo"
}

@Serializable
data class RepoInfo(
    val url: String,
    val name: String,
    val enabled: Boolean = true,
    val fingerprint: String? = null
)
