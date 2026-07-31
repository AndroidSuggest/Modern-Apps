package com.vayunmathur.appstore.data

enum class AppSource {
    FDROID,
    PLAYSTORE
}

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
    val targetSdk: Int? = null,
    val license: String? = null,
    val website: String? = null,
    val sourceCode: String? = null,
    val whatsNew: String? = null,
    val addedTimestamp: Long = 0L,
    val lastUpdated: Long = 0L,
    val antiFeatures: List<String> = emptyList(),
    val repoUrl: String? = null,
    val offerType: Int = 0,
    val rating: Float? = null,
    val containsSplit: Boolean = false,
    val isFree: Boolean = true
)

data class InstalledInfo(
    val packageName: String,
    val name: String,
    val versionName: String?,
    val versionCode: Long,
    val enabled: Boolean = true
)

object DefaultRepos {
    const val FDROID_MAIN = "https://f-droid.org/repo"
    const val FDROID_ARCHIVE = "https://f-droid.org/archive"
    const val IZVYZID = "https://apt.izzysoft.de/fdroid/repo"
}
