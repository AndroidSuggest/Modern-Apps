package com.vayunmathur.appstore.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * The monorepo's own apps, sourced straight from its GitHub releases.
 *
 * `release.sh` attaches every module's release APK to the tagged GitHub release, together
 * with an `index.json` describing them — the APK filenames alone can't be mapped back to
 * package names, since `:games:chess` builds `chess-release.apk` but installs as
 * `com.vayunmathur.games.chess`.
 *
 * Releases cut before `index.json` existed are still usable: [fetchFromGitTree] derives
 * the same information from the tag itself, by listing the repo tree for that tag and
 * turning each module path into its applicationId (`games/chess` →
 * `com.vayunmathur.games.chess`, which holds for every module in the repo). Version comes
 * from `version.txt` at the tag and per-asset SHA-256 from GitHub's own `digest` field, so
 * the fallback is exact rather than a guess.
 *
 * **Neither path is signed, and neither needs to be.** Every APK from this source is
 * checked at install time against the certificate *this store app itself* is signed with
 * (see [com.vayunmathur.appstore.data.security.ApkCertificates.selfSigners]). A tampered
 * index, a compromised GitHub account, or a hostile CDN can change which bytes are
 * offered, but cannot produce bytes that will install: that needs the release signing
 * key, which is the sole trust root here.
 *
 * Reads go through [CatalogRepository], which queries the cache table this writes.
 */
class ModernAppsProvider(private val appContext: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private suspend fun fetchAll(): List<UnifiedApp> = withContext(Dispatchers.IO) {
        val release = json.decodeFromString(
            GithubRelease.serializer(),
            httpGetString(ModernAppsRepo.LATEST_RELEASE_API, accept = GITHUB_ACCEPT),
        )
        val indexAsset = release.assets.firstOrNull { it.name == ModernAppsRepo.INDEX_ASSET }
        val apps = if (indexAsset != null) {
            fetchFromIndex(release, indexAsset)
        } else {
            fetchFromGitTree(release)
        }
        AppProvider.filterTargetSdk(apps).distinctBy { it.packageName }
    }

    /** Preferred path: the release states its own contents. */
    private fun fetchFromIndex(release: GithubRelease, indexAsset: GithubAsset): List<UnifiedApp> {
        val index = json.decodeFromString(
            ModernAppsIndex.serializer(),
            httpGetString(indexAsset.downloadUrl, accept = "application/json"),
        )
        val assets = release.assets.associateBy { it.name }
        return index.apps.mapNotNull { entry ->
            val asset = assets[entry.apk] ?: return@mapNotNull null
            entryToApp(
                packageName = entry.packageName,
                label = entry.label,
                asset = asset,
                versionCode = entry.versionCode ?: index.versionCode,
                versionName = entry.versionName ?: index.versionName ?: release.tagName,
                targetSdk = entry.targetSdk,
                sha256 = entry.sha256 ?: asset.sha256(),
                summary = entry.summary.orEmpty(),
                description = entry.description.orEmpty(),
                publishedAt = release.publishedAtMillis(),
            )
        }
    }

    /**
     * Fallback for releases published before `index.json` existed.
     *
     * Every module in the repo has `applicationId = "com.vayunmathur." + <module path with
     * dots>`, and each builds `<leaf>-release.apk`, so the tag's file listing is enough to
     * recover the mapping exactly. Where a leaf name is ambiguous (`openassistant` is both
     * an app and an `sdk/` library) the shallowest path wins, which is the app; a wrong
     * guess could not install anything anyway, because [InstallVerifier] rejects an APK
     * whose declared package differs from the one requested.
     */
    private fun fetchFromGitTree(release: GithubRelease): List<UnifiedApp> {
        val tag = release.tagName.ifBlank { throw java.io.IOException("Release has no tag") }
        val tree = json.decodeFromString(
            GitTree.serializer(),
            httpGetString(ModernAppsRepo.treeApi(tag), accept = GITHUB_ACCEPT),
        )
        val modules = tree.tree.asSequence()
            .map { it.path }
            .filter { it.endsWith("/build.gradle.kts") && !it.startsWith("build-logic") }
            .map { it.removeSuffix("/build.gradle.kts") }
            .toList()

        // leaf -> shallowest module path with that leaf
        val byLeaf = HashMap<String, String>()
        for (module in modules.sortedBy { it.count { c -> c == '/' } }) {
            byLeaf.putIfAbsent(module.substringAfterLast('/'), module)
        }

        // release.sh injects one version across every module; version.txt at the tag
        // records it, and GitHub's asset list carries no versionCode of its own.
        val (versionCode, versionName) = readVersionTxt(tag) ?: (0L to tag)

        return release.assets.mapNotNull { asset ->
            if (!asset.name.endsWith(APK_SUFFIX)) return@mapNotNull null
            val leaf = asset.name.removeSuffix(APK_SUFFIX)
            val module = byLeaf[leaf] ?: return@mapNotNull null
            entryToApp(
                packageName = "com.vayunmathur." + module.replace('/', '.'),
                label = leaf.replaceFirstChar { it.uppercase() },
                asset = asset,
                versionCode = versionCode,
                versionName = versionName,
                // Unknown without reading the APK; null passes filterTargetSdk, and the
                // whole repo targets well above the floor anyway.
                targetSdk = null,
                sha256 = asset.sha256(),
                summary = "",
                description = "",
                publishedAt = release.publishedAtMillis(),
            )
        }
    }

    /** `version.txt` is two lines: versionCode, then versionName. */
    private fun readVersionTxt(tag: String): Pair<Long, String>? = try {
        val lines = httpGetString(ModernAppsRepo.versionTxt(tag), accept = "text/plain")
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val code = lines.getOrNull(0)?.toLongOrNull()
        val name = lines.getOrNull(1)
        if (code != null && name != null) code to name else null
    } catch (_: Exception) {
        null
    }

    private fun entryToApp(
        packageName: String,
        label: String,
        asset: GithubAsset,
        versionCode: Long,
        versionName: String,
        targetSdk: Int?,
        sha256: String?,
        summary: String,
        description: String,
        publishedAt: Long,
    ) = UnifiedApp(
        packageName = packageName,
        source = AppSource.MODERN_APPS,
        name = label,
        summary = summary,
        description = description,
        author = AUTHOR,
        versionName = versionName,
        versionCode = versionCode,
        sizeBytes = asset.size,
        apkUrl = asset.downloadUrl,
        targetSdk = targetSdk,
        license = LICENSE,
        website = ModernAppsRepo.PROJECT_URL,
        sourceCode = ModernAppsRepo.PROJECT_URL,
        repoUrl = ModernAppsRepo.REPO_KEY,
        apkSha256 = sha256,
        lastUpdated = publishedAt,
    )

    /** Refresh from GitHub and replace this source's rows in the shared cache table. */
    suspend fun syncIntoDb(db: AppDatabase): Int = withContext(Dispatchers.IO) {
        val apps = fetchAll()
        db.cachedAppDao().deleteByRepo(ModernAppsRepo.REPO_KEY)
        db.cachedAppDao().upsertAll(apps.map { it.toEntity() })
        apps.size
    }

    private fun httpGetString(url: String, accept: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw java.io.IOException("HTTP ${conn.responseCode} for $url")
            }
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val GITHUB_ACCEPT = "application/vnd.github+json"
        private const val USER_AGENT = "ModernAppStore/1.0"
        private const val AUTHOR = "Vayun Mathur"
        private const val LICENSE = "GPL-3.0-only"
        private const val APK_SUFFIX = "-release.apk"
    }
}

// --- Wire formats -------------------------------------------------------------------

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GithubAsset> = emptyList(),
) {
    /** GitHub returns ISO-8601 UTC ("2026-08-01T12:00:00Z"); 0 if absent or unparseable. */
    fun publishedAtMillis(): Long = publishedAt?.let {
        runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
    } ?: 0L
}

@Serializable
private data class GithubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val size: Long = 0L,
    /** GitHub returns `"sha256:<hex>"`; absent on older releases. */
    val digest: String? = null,
) {
    fun sha256(): String? = digest?.removePrefix("sha256:")?.takeIf { it.length == 64 }
}

@Serializable
private data class GitTree(val tree: List<GitTreeEntry> = emptyList())

@Serializable
private data class GitTreeEntry(val path: String = "")

@Serializable
private data class ModernAppsIndex(
    val versionCode: Long = 0L,
    val versionName: String? = null,
    val apps: List<ModernAppEntry> = emptyList(),
)

@Serializable
private data class ModernAppEntry(
    val packageName: String,
    val label: String,
    /** Release asset filename, e.g. `chess-release.apk`. */
    val apk: String,
    val sha256: String? = null,
    val size: Long = 0L,
    val versionCode: Long? = null,
    val versionName: String? = null,
    val targetSdk: Int? = null,
    val summary: String? = null,
    val description: String? = null,
)
