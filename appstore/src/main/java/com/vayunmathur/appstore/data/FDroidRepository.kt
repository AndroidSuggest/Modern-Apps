package com.vayunmathur.appstore.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import com.vayunmathur.appstore.data.security.ApkCertificates
import com.vayunmathur.appstore.data.security.SignedJarIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * F-Droid repo client. Avoids OOM on the ~100MB index by downloading to a file and
 * streaming-parsing with android.util.JsonReader rather than holding a whole object tree.
 * Filters targetSdk < AppProvider.MIN_TARGET_SDK.
 *
 * **The index is always taken from the repo's signed JAR** (`entry.jar` for index-v2,
 * `index-v1.jar` for the legacy format) and the signing certificate is pinned per repo.
 * The plain `index-v2.json` endpoint is deliberately *not* used as a fallback: without a
 * signature the per-APK `sha256` and `signer` values this parser extracts would be
 * attacker-controlled, and pinning them would be security theatre.
 */
object FDroidRepository {

    /**
     * SHA-256 of the certificate f-droid.org signs its index with (CN=Ciaran Gultnieks).
     * Hard-pinned rather than trust-on-first-use because there is exactly one supported
     * repository, so there is no reason to ever accept an unknown key for it.
     */
    const val FDROID_SIGNING_CERT_SHA256 =
        "43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab"

    /** Parsed index plus the repo signing certificate it was authenticated with. */
    data class IndexResult(val apps: List<UnifiedApp>, val signerSha256: String)

    /**
     * Fetch and verify [repoUrl]'s index.
     *
     * [pinnedFingerprint] null means trust-on-first-use, and the caller must persist
     * [IndexResult.signerSha256]. [acceptVersion] decides which versions of a package may
     * be offered at all — the newest *accepted* version wins, so a package whose latest
     * build has not yet been reproduced falls back to its newest reproduced one rather
     * than disappearing.
     */
    suspend fun fetchRepoIndex(
        context: Context,
        repoUrl: String,
        pinnedFingerprint: String?,
        acceptVersion: (packageName: String, versionCode: Long) -> Boolean,
    ): IndexResult = withContext(Dispatchers.IO) {
        val base = repoUrl.trimEnd('/')
        val work = File(context.cacheDir, "fdroid-index/${base.hashCode()}").apply { mkdirs() }
        try {
            val errors = mutableListOf<String>()
            try {
                return@withContext fetchV2(base, pinnedFingerprint, work, acceptVersion)
            } catch (e: Exception) {
                errors += "index-v2: ${e.message}"
            }
            try {
                return@withContext fetchV1(base, pinnedFingerprint, work, acceptVersion)
            } catch (e: Exception) {
                errors += "index-v1: ${e.message}"
            }
            throw java.io.IOException("Could not load a signed index from $base (${errors.joinToString("; ")})")
        } finally {
            work.deleteRecursively()
        }
    }

    /**
     * index-v2: `entry.jar` is the signed root. It names the real index file and pins its
     * SHA-256, so the large index itself needs no separate signature — the hash chains
     * back to the certificate we just verified.
     */
    private fun fetchV2(
        base: String,
        pinnedFingerprint: String?,
        work: File,
        acceptVersion: (String, Long) -> Boolean,
    ): IndexResult {
        val entryJar = File(work, "entry.jar")
        downloadToFile("$base/entry.jar", entryJar)
        val verified = SignedJarIndex.readVerified(entryJar, "entry.json", pinnedFingerprint)

        val entry = JSONObject(String(verified.content, Charsets.UTF_8))
        val index = entry.optJSONObject("index")
            ?: throw java.io.IOException("entry.json has no index section")
        val name = index.optString("name").takeIf { it.isNotBlank() }
            ?: throw java.io.IOException("entry.json index has no name")
        val expectedSha = index.optString("sha256").takeIf { it.isNotBlank() }
            ?: throw java.io.IOException("entry.json index has no sha256")

        val indexFile = File(work, "index-v2.json")
        downloadToFile(base + "/" + name.trimStart('/'), indexFile)
        val actualSha = ApkCertificates.sha256(indexFile)
        if (!actualSha.equals(expectedSha, ignoreCase = true)) {
            throw java.io.IOException("index-v2.json hash does not match the signed entry.json")
        }

        return IndexResult(
            apps = AppProvider.filterTargetSdk(parseV2Streaming(indexFile, base, acceptVersion)),
            signerSha256 = verified.signerSha256,
        )
    }

    /** Legacy format: the whole index is inside the signed JAR. */
    private fun fetchV1(
        base: String,
        pinnedFingerprint: String?,
        work: File,
        acceptVersion: (String, Long) -> Boolean,
    ): IndexResult {
        val jar = File(work, "index-v1.jar")
        downloadToFile("$base/index-v1.jar", jar)
        val indexFile = File(work, "index-v1.json")
        val signer = SignedJarIndex.extractVerified(jar, "index-v1.json", pinnedFingerprint, indexFile)
        return IndexResult(
            apps = AppProvider.filterTargetSdk(parseV1Streaming(indexFile, base, acceptVersion)),
            signerSha256 = signer,
        )
    }

    internal fun downloadToFile(url: String, outFile: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30000
            readTimeout = 120000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ModernAppStore/1.0")
            instanceFollowRedirects = true
        }
        if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode} for $url")
        conn.inputStream.use { input ->
            outFile.outputStream().use { out ->
                val buf = ByteArray(32 * 1024)
                var n: Int
                while (input.read(buf).also { n = it } != -1) out.write(buf, 0, n)
            }
        }
    }

    // ---- Streaming V2 parser ----

    private fun parseV2Streaming(
        file: File,
        repoBase: String,
        acceptVersion: (String, Long) -> Boolean,
    ): List<UnifiedApp> {
        val result = mutableListOf<UnifiedApp>()
        JsonReader(file.reader()).use { r ->
            r.isLenient = true
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "packages" -> {
                        r.beginObject()
                        while (r.hasNext()) {
                            val pkg = r.nextName()
                            try {
                                val app = parsePackageV2(r, pkg, repoBase, acceptVersion)
                                if (app != null) result.add(app)
                            } catch (_: Exception) {
                                try { r.skipValue() } catch (_: Exception) {}
                            }
                        }
                        r.endObject()
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }
        return result
    }

    private fun parsePackageV2(
        reader: JsonReader,
        packageName: String,
        repoBase: String,
        acceptVersion: (String, Long) -> Boolean,
    ): UnifiedApp? {
        // reader at BEGIN_OBJECT of package
        var metaName: String? = null
        var metaSummary: String? = null
        var metaDesc: String? = null
        var author: String? = null
        var categories: List<String> = emptyList()
        var website: String? = null
        var sourceCode: String? = null
        var license: String? = null
        var added: Long = 0L
        var lastUpdated: Long = 0L
        var iconUrl: String? = null
        var latestAdded: Long = -1L
        var latestFileName: String? = null
        var latestSize: Long = 0L
        var latestSha256: String? = null
        var latestSigners: List<String> = emptyList()
        var latestVersionName: String? = null
        var latestVersionCode: Long = 0L
        var latestTargetSdk: Int? = null
        var latestWhatsNew: String? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "metadata" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (val mk = reader.nextName()) {
                            "name" -> metaName = readLocalizedString(reader)
                            "summary" -> metaSummary = readLocalizedString(reader)
                            "description" -> metaDesc = readLocalizedString(reader)
                            "authorName" -> author = nextStringOrNull(reader)
                            "categories" -> categories = readStringArray(reader)
                            "webSite" -> website = nextStringOrNull(reader)
                            "sourceCode" -> sourceCode = nextStringOrNull(reader)
                            "license" -> license = nextStringOrNull(reader)
                            "added" -> added = nextLongOrNull(reader) ?: 0L
                            "lastUpdated" -> lastUpdated = nextLongOrNull(reader) ?: 0L
                            "icon" -> {
                                // index-v2 icon names are repo-absolute ("/icons/foo.png");
                                // don't prepend /icons/ again as the v1 branch has to.
                                val iconName = readIconName(reader)
                                if (iconName != null) iconUrl = repoBase + "/" + iconName.trimStart('/')
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                "versions" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        reader.nextName() // version key
                        try {
                            reader.beginObject()
                            var vAdded: Long = 0L
                            var vFileName: String? = null
                            var vFileSize: Long = 0L
                            var vFileSha256: String? = null
                            var vSigners: List<String> = emptyList()
                            var vVersionName: String? = null
                            var vVersionCode: Long = 0L
                            var vTargetSdk: Int? = null
                            var vWhatsNew: String? = null
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "added" -> vAdded = nextLongOrNull(reader) ?: 0L
                                    "file" -> {
                                        reader.beginObject()
                                        while (reader.hasNext()) {
                                            when (reader.nextName()) {
                                                "name" -> vFileName = nextStringOrNull(reader)
                                                "size" -> vFileSize = nextLongOrNull(reader) ?: 0L
                                                "sha256" -> vFileSha256 = nextStringOrNull(reader)
                                                else -> reader.skipValue()
                                            }
                                        }
                                        reader.endObject()
                                    }
                                    "manifest" -> {
                                        reader.beginObject()
                                        while (reader.hasNext()) {
                                            when (reader.nextName()) {
                                                "versionName" -> vVersionName = nextStringOrNull(reader)
                                                "versionCode" -> vVersionCode = nextLongOrNull(reader) ?: 0L
                                                "usesSdk" -> {
                                                    reader.beginObject()
                                                    while (reader.hasNext()) {
                                                        when (reader.nextName()) {
                                                            "targetSdkVersion" -> vTargetSdk = nextIntOrNull(reader)
                                                            else -> reader.skipValue()
                                                        }
                                                    }
                                                    reader.endObject()
                                                }
                                                // signer.sha256 is the list of signing-certificate
                                                // fingerprints this APK is expected to carry.
                                                "signer" -> {
                                                    reader.beginObject()
                                                    while (reader.hasNext()) {
                                                        when (reader.nextName()) {
                                                            "sha256" -> vSigners = readStringArray(reader)
                                                            else -> reader.skipValue()
                                                        }
                                                    }
                                                    reader.endObject()
                                                }
                                                else -> reader.skipValue()
                                            }
                                        }
                                        reader.endObject()
                                    }
                                    "whatsNew" -> vWhatsNew = readLocalizedString(reader)
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            // Newest *acceptable* version wins: a package whose latest build
                            // hasn't been reproduced yet falls back to its newest reproduced
                            // one instead of vanishing from the catalogue.
                            if (vAdded >= latestAdded && acceptVersion(packageName, vVersionCode)) {
                                latestAdded = vAdded
                                latestFileName = vFileName
                                latestSize = vFileSize
                                latestSha256 = vFileSha256
                                latestSigners = vSigners
                                latestVersionName = vVersionName
                                latestVersionCode = vVersionCode
                                latestTargetSdk = vTargetSdk
                                latestWhatsNew = vWhatsNew
                            }
                        } catch (_: Exception) {
                            try { reader.endObject() } catch (_: Exception) {}
                        }
                    }
                    reader.endObject()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        // No version passed acceptVersion — drop the package entirely rather than
        // advertising an entry that has no installable APK behind it.
        if (latestAdded < 0L || latestFileName == null) return null

        // index-v2 file names are repo-absolute ("/com.example_12.apk").
        val apkUrl = latestFileName.let { repoBase + "/" + it.trimStart('/') }
        return UnifiedApp(
            packageName = packageName,
            source = AppSource.FDROID,
            expectedSigners = latestSigners,
            apkSha256 = latestSha256,
            name = metaName ?: packageName.substringAfterLast('.'),
            summary = metaSummary ?: "",
            description = metaDesc ?: "",
            iconUrl = iconUrl,
            author = author,
            categories = categories,
            versionName = latestVersionName,
            versionCode = latestVersionCode,
            sizeBytes = latestSize,
            apkUrl = apkUrl,
            targetSdk = latestTargetSdk,
            license = license,
            website = website,
            sourceCode = sourceCode,
            whatsNew = latestWhatsNew,
            addedTimestamp = added,
            lastUpdated = if (lastUpdated != 0L) lastUpdated else added,
            repoUrl = repoBase
        )
    }

    // ---- Streaming V1 parser (apps[] + packages{ pkg:[...] }) ----

    private fun parseV1Streaming(
        file: File,
        repoBase: String,
        acceptVersion: (String, Long) -> Boolean,
    ): List<UnifiedApp> {
        val appsMeta = mutableMapOf<String, V1AppMeta>()
        val packagesMap = mutableMapOf<String, V1PackageLatest>()
        JsonReader(file.reader()).use { r ->
            r.isLenient = true
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "apps" -> {
                        r.beginArray()
                        while (r.hasNext()) {
                            try {
                                val meta = parseV1AppMeta(r)
                                if (meta != null) appsMeta[meta.packageName] = meta
                            } catch (_: Exception) {
                                try { r.skipValue() } catch (_: Exception) {}
                            }
                        }
                        r.endArray()
                    }
                    "packages" -> {
                        r.beginObject()
                        while (r.hasNext()) {
                            val pkgName = r.nextName()
                            try {
                                val latest = parseV1PackagesArray(r, pkgName, acceptVersion)
                                if (latest != null) packagesMap[pkgName] = latest
                            } catch (_: Exception) {
                                try { r.skipValue() } catch (_: Exception) {}
                            }
                        }
                        r.endObject()
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }

        val result = mutableListOf<UnifiedApp>()
        for ((pkg, meta) in appsMeta) {
            // Same rule as v2: no acceptable version means no catalogue entry.
            val latest = packagesMap[pkg] ?: continue
            result.add(
                UnifiedApp(
                    packageName = pkg,
                    source = AppSource.FDROID,
                    name = meta.name ?: pkg.substringAfterLast('.'),
                    summary = meta.summary ?: "",
                    description = meta.description ?: "",
                    iconUrl = meta.icon?.let { "$repoBase/icons/$it" },
                    author = meta.authorName,
                    categories = meta.categories,
                    versionName = latest?.versionName,
                    versionCode = latest?.versionCode ?: 0L,
                    sizeBytes = latest?.size ?: 0L,
                    apkUrl = latest?.apkName?.let { repoBase + "/" + it.trimStart('/') },
                    targetSdk = latest?.targetSdk,
                    expectedSigners = listOfNotNull(latest?.signer),
                    // v1 states the hash algorithm; only pin it when it really is SHA-256.
                    apkSha256 = latest?.hash?.takeIf { latest?.hashType.equals("sha256", true) },
                    website = meta.webSite,
                    sourceCode = meta.sourceCode,
                    license = meta.license,
                    addedTimestamp = meta.added,
                    lastUpdated = meta.lastUpdated,
                    repoUrl = repoBase
                )
            )
        }
        return result
    }

    private data class V1AppMeta(
        val packageName: String,
        val name: String?,
        val summary: String?,
        val description: String?,
        val icon: String?,
        val authorName: String?,
        val categories: List<String>,
        val webSite: String?,
        val sourceCode: String?,
        val license: String?,
        val added: Long,
        val lastUpdated: Long
    )

    private data class V1PackageLatest(
        val apkName: String?,
        val versionName: String?,
        val versionCode: Long,
        val size: Long,
        val targetSdk: Int?,
        val hash: String?,
        val hashType: String?,
        val signer: String?
    )

    private fun parseV1AppMeta(reader: JsonReader): V1AppMeta? {
        var packageName: String? = null
        var name: String? = null
        var summary: String? = null
        var description: String? = null
        var icon: String? = null
        var authorName: String? = null
        var categories: List<String> = emptyList()
        var webSite: String? = null
        var sourceCode: String? = null
        var license: String? = null
        var added: Long = 0L
        var lastUpdated: Long = 0L

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "packageName" -> packageName = nextStringOrNull(reader)
                "name" -> name = nextStringOrNull(reader)
                "summary" -> summary = nextStringOrNull(reader)
                "description" -> description = nextStringOrNull(reader)
                "icon" -> icon = nextStringOrNull(reader)
                "authorName" -> authorName = nextStringOrNull(reader)
                "categories" -> categories = readStringArray(reader)
                "webSite" -> webSite = nextStringOrNull(reader)
                "sourceCode" -> sourceCode = nextStringOrNull(reader)
                "license" -> license = nextStringOrNull(reader)
                "added" -> added = nextLongOrNull(reader) ?: 0L
                "lastUpdated" -> lastUpdated = nextLongOrNull(reader) ?: 0L
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val pkg = packageName ?: return null
        return V1AppMeta(pkg, name, summary, description, icon, authorName, categories, webSite, sourceCode, license, added, lastUpdated)
    }

    private fun parseV1PackagesArray(
        reader: JsonReader,
        packageName: String,
        acceptVersion: (String, Long) -> Boolean,
    ): V1PackageLatest? {
        // array of package versions, take the last acceptable one
        var last: V1PackageLatest? = null
        reader.beginArray()
        while (reader.hasNext()) {
            try {
                reader.beginObject()
                var apkName: String? = null
                var versionName: String? = null
                var versionCode: Long = 0L
                var size: Long = 0L
                var targetSdk: Int? = null
                var hash: String? = null
                var hashType: String? = null
                var signer: String? = null
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "apkName" -> apkName = nextStringOrNull(reader)
                        "versionName" -> versionName = nextStringOrNull(reader)
                        "versionCode" -> versionCode = nextLongOrNull(reader) ?: 0L
                        "size" -> size = nextLongOrNull(reader) ?: 0L
                        "targetSdkVersion" -> targetSdk = nextIntOrNull(reader)
                        "hash" -> hash = nextStringOrNull(reader)
                        "hashType" -> hashType = nextStringOrNull(reader)
                        "signer" -> signer = nextStringOrNull(reader)
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                if (acceptVersion(packageName, versionCode)) {
                    last = V1PackageLatest(
                        apkName, versionName, versionCode, size, targetSdk, hash, hashType, signer
                    )
                }
            } catch (_: Exception) {
                try { reader.endObject() } catch (_: Exception) {}
                try { reader.skipValue() } catch (_: Exception) {}
            }
        }
        reader.endArray()
        return last
    }

    // ---- Helpers ----

    private fun readLocalizedString(reader: JsonReader): String? {
        return when (reader.peek()) {
            JsonToken.STRING -> reader.nextString()
            JsonToken.BEGIN_OBJECT -> {
                var first: String? = null
                var enUs: String? = null
                var en: String? = null
                reader.beginObject()
                while (reader.hasNext()) {
                    val locale = reader.nextName()
                    when (reader.peek()) {
                        JsonToken.STRING -> {
                            val v = reader.nextString()
                            if (first == null) first = v
                            if (locale == "en-US") enUs = v
                            if (locale == "en") en = v
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                enUs ?: en ?: first
            }
            else -> { reader.skipValue(); null }
        }
    }

    private fun readIconName(reader: JsonReader): String? {
        // icon can be: { "en-US": { "96": {"name":...} } } or { "en-US": {"name":...} }
        return try {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return null }
            var found: String? = null
            reader.beginObject()
            while (reader.hasNext() && found == null) {
                reader.nextName() // locale
                found = findFirstNameInAnyNested(reader)
            }
            while (reader.hasNext()) { reader.nextName(); reader.skipValue() }
            reader.endObject()
            found
        } catch (_: Exception) { null }
    }

    private fun findFirstNameInAnyNested(reader: JsonReader): String? {
        // searches recursively for first object containing key "name" = String
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                var found: String? = null
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    if (key == "name" && reader.peek() == JsonToken.STRING) {
                        val v = reader.nextString()
                        if (found == null) found = v
                        // keep consuming remaining to properly close
                    } else {
                        if (found == null && reader.peek() == JsonToken.BEGIN_OBJECT) {
                            val inner = findFirstNameInAnyNested(reader)
                            if (inner != null) found = inner
                        } else if (found == null && reader.peek() == JsonToken.BEGIN_ARRAY) {
                            reader.skipValue()
                        } else {
                            reader.skipValue()
                        }
                    }
                }
                reader.endObject()
                found
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                var found: String? = null
                while (reader.hasNext() && found == null) {
                    found = findFirstNameInAnyNested(reader)
                }
                while (reader.hasNext()) reader.skipValue()
                reader.endArray()
                found
            }
            else -> { reader.skipValue(); null }
        }
    }

    private fun readStringArray(reader: JsonReader): List<String> {
        return try {
            if (reader.peek() != JsonToken.BEGIN_ARRAY) { reader.skipValue(); return emptyList() }
            val list = mutableListOf<String>()
            reader.beginArray()
            while (reader.hasNext()) {
                if (reader.peek() == JsonToken.STRING) list.add(reader.nextString()) else reader.skipValue()
            }
            reader.endArray()
            list
        } catch (_: Exception) { emptyList() }
    }

    private fun nextStringOrNull(reader: JsonReader): String? {
        return try {
            when (reader.peek()) {
                JsonToken.STRING -> reader.nextString()
                JsonToken.NULL -> { reader.nextNull(); null }
                JsonToken.NUMBER -> reader.nextString()
                else -> { reader.skipValue(); null }
            }
        } catch (_: Exception) { null }
    }

    private fun nextLongOrNull(reader: JsonReader): Long? {
        return try {
            when (reader.peek()) {
                JsonToken.NUMBER -> reader.nextLong()
                JsonToken.STRING -> reader.nextString().toLongOrNull()
                JsonToken.NULL -> { reader.nextNull(); null }
                else -> { reader.skipValue(); null }
            }
        } catch (_: Exception) { null }
    }

    private fun nextIntOrNull(reader: JsonReader): Int? {
        return try {
            when (reader.peek()) {
                JsonToken.NUMBER -> reader.nextInt()
                JsonToken.STRING -> reader.nextString().toIntOrNull()
                JsonToken.NULL -> { reader.nextNull(); null }
                else -> { reader.skipValue(); null }
            }
        } catch (_: Exception) { null }
    }
}
