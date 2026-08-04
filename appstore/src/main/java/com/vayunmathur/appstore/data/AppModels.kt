package com.vayunmathur.appstore.data

enum class AppSource {
    /** This monorepo's own GitHub releases, signed with the same key as this app. */
    MODERN_APPS,
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
    /**
     * True when this exact version (packageName + versionCode) was independently
     * reproduced bit-for-bit by F-Droid's verification server. F-Droid only; a badge,
     * not a gate — apps are listed regardless. See [com.vayunmathur.appstore.data.ReproducibleBuilds].
     */
    val reproducible: Boolean = false,
    val license: String? = null,
    val website: String? = null,
    val sourceCode: String? = null,
    val whatsNew: String? = null,
    val addedTimestamp: Long = 0L,
    val lastUpdated: Long = 0L,
    val antiFeatures: List<String> = emptyList(),
    val repoUrl: String? = null,
    val offerType: Int = 0,
    val containsSplit: Boolean = false,
    val isFree: Boolean = true,
    /** Listing screenshots, in the order the source published them. */
    val screenshots: List<String> = emptyList(),
    /** Wide header image, where the source publishes one. */
    val featureGraphic: String? = null,
    /** Average star rating, 0..5. Null when the source doesn't publish one. */
    val rating: Float? = null,
    /** How many ratings [rating] is an average of, 0 when unknown. */
    val ratingCount: Long = 0L,
    /** Install count as the source reports it, 0 when unknown. */
    val installs: Long = 0L,
    /** Human-readable release date from the source, e.g. "Jul 28, 2026". */
    val updatedOn: String? = null,
    /** Age rating label, e.g. "Everyone" / "PEGI 3". */
    val contentRating: String? = null,
    val privacyPolicyUrl: String? = null,
    val containsAds: Boolean = false,
    /** Permission names the listing declares, before install. */
    val permissions: List<String> = emptyList(),
    /**
     * Lowercase-hex SHA-256 fingerprints of the certificates the publisher says this
     * APK is signed with, from a source we authenticated (a JAR-signed F-Droid index).
     * Empty when the source can't tell us — notably Play, where Google holds the key.
     */
    val expectedSigners: List<String> = emptyList(),
    /** Lowercase-hex SHA-256 of the APK itself, when the source publishes one. */
    val apkSha256: String? = null,
) {
    /** The single best image to head the detail page with. */
    val heroImage: String? get() = featureGraphic ?: screenshots.firstOrNull()
}

data class InstalledInfo(
    val packageName: String,
    val name: String,
    val versionName: String?,
    val versionCode: Long,
    val enabled: Boolean = true,
    /** When the package was last updated on this device, ms since epoch. */
    val lastUpdateTime: Long = 0L,
)

/**
 * The one and only F-Droid repository this store will talk to.
 *
 * Third-party F-Droid-format repos (IzzyOnDroid and friends) are deliberately not
 * supported and cannot be added: they republish binaries built by the upstream developer
 * rather than building from source, so an app from one carries the developer's release
 * pipeline *and* the mirror operator in its trusted set, with no published source to
 * check the binary against. The f-droid.org archive is excluded for a different reason —
 * it exists to serve superseded versions, which is the opposite of what we want.
 */
object DefaultRepos {
    const val FDROID_MAIN = "https://f-droid.org/repo"
}

/** The Modern-Apps monorepo's own GitHub release channel. */
object ModernAppsRepo {
    const val OWNER_REPO = "vayun-mathur/Modern-Apps"
    const val PROJECT_URL = "https://github.com/$OWNER_REPO"
    const val LATEST_RELEASE_API = "https://api.github.com/repos/$OWNER_REPO/releases/latest"

    /** Asset published by `release.sh` alongside the APKs; see [ModernAppsProvider]. */
    const val INDEX_ASSET = "index.json"

    /** File listing for a tag, used to recover package names on pre-index releases. */
    fun treeApi(tag: String) =
        "https://api.github.com/repos/$OWNER_REPO/git/trees/$tag?recursive=1"

    /** Two lines: versionCode, versionName. Injected into every module by `release.sh`. */
    fun versionTxt(tag: String) =
        "https://raw.githubusercontent.com/$OWNER_REPO/$tag/version.txt"

    /** Pseudo repo URL, used as the `repoUrl` key for cached rows. */
    const val REPO_KEY = PROJECT_URL
}
