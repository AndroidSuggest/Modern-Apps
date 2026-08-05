package com.vayunmathur.appstore.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import com.vayunmathur.appstore.data.security.ApkCertificates
import com.vayunmathur.appstore.data.security.SignedJarIndex
import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * F-Droid repo client. Avoids OOM on the ~100MB index by downloading to a file and
 * streaming-parsing with android.util.JsonReader rather than holding a whole object tree.
 * Filters targetSdk < AppProvider.MIN_TARGET_SDK.
 *
 * **The index is always authenticated through the repo's signed `entry.jar`**, and the
 * signing certificate is pinned per repo. The plain `index-v2.json` endpoint is deliberately
 * *not* used as a fallback: without a
 * signature the per-APK `sha256` and `signer` values this parser extracts would be
 * attacker-controlled, and pinning them would be security theatre.
 */
object FDroidRepository {

    /** Parsed index plus the repo signing certificate it was authenticated with. */
    data class IndexResult(val apps: List<UnifiedApp>, val signerSha256: String)

    /**
     * Fetch and verify [repoUrl]'s index.
     *
     * Every app's newest version is imported with [source]; [isReproducible]
     * only *tags* whether that version was independently reproduced (surfaced as a badge),
     * it never drops a version or a package.
     */
    suspend fun fetchRepoIndex(
        context: Context,
        repoUrl: String,
        pinnedFingerprint: String,
        source: AppSource,
        isReproducible: (packageName: String, versionCode: Long) -> Boolean,
    ): IndexResult = withContext(Dispatchers.IO) {
        val base = repoUrl.trimEnd('/')
        val work = File(context.cacheDir, "fdroid-index/${base.hashCode()}").apply { mkdirs() }
        try {
            fetchV2(base, pinnedFingerprint, source, work, isReproducible)
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
        pinnedFingerprint: String,
        source: AppSource,
        work: File,
        isReproducible: (String, Long) -> Boolean,
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
            apps = AppProvider.filterTargetSdk(
                parseV2Streaming(indexFile, base, source, isReproducible)
            ),
            signerSha256 = verified.signerSha256,
        )
    }

    internal fun downloadToFile(url: String, outFile: File) {
        val rawConnection = URL(url).openConnection()
        val sslSocketFactory = NetworkClient.defaultSslSocketFactory
        if (sslSocketFactory != null && rawConnection is HttpsURLConnection) {
            rawConnection.sslSocketFactory = sslSocketFactory
        }
        val conn = (rawConnection as HttpURLConnection).apply {
            connectTimeout = 30000
            readTimeout = 120000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ModernAppStore/1.0")
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw java.io.IOException("HTTP ${conn.responseCode} for $url")
            }
            conn.inputStream.use { input ->
                outFile.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    // ---- Streaming V2 parser ----

    private fun parseV2Streaming(
        file: File,
        repoBase: String,
        source: AppSource,
        isReproducible: (String, Long) -> Boolean,
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
                                val app = parsePackageV2(r, pkg, repoBase, source, isReproducible)
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
        source: AppSource,
        isReproducible: (String, Long) -> Boolean,
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
        var featureGraphic: String? = null
        var screenshots: List<String> = emptyList()
        var antiFeatures: List<String> = emptyList()
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
                            "featureGraphic" -> {
                                val name = readIconName(reader)
                                if (name != null) featureGraphic = repoBase + "/" + name.trimStart('/')
                            }
                            "screenshots" -> screenshots = readScreenshotsV2(reader, repoBase)
                            // v2 states anti-features as a map of id -> localised reason;
                            // the ids are what the UI shows, so only the keys are kept.
                            "antiFeatures" -> antiFeatures = readObjectKeys(reader)
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
                            // Newest version always wins; reproducibility is recorded as a
                            // badge below, not used to drop older-but-reproduced builds.
                            if (vAdded >= latestAdded) {
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

        // No usable version at all — drop the package rather than advertising an entry
        // that has no installable APK behind it.
        if (latestAdded < 0L || latestFileName == null) return null

        // index-v2 file names are repo-absolute ("/com.example_12.apk").
        val apkUrl = latestFileName.let { repoBase + "/" + it.trimStart('/') }
        return UnifiedApp(
            packageName = packageName,
            source = source,
            expectedSigners = latestSigners,
            apkSha256 = latestSha256,
            reproducible = isReproducible(packageName, latestVersionCode),
            name = metaName ?: packageName.substringAfterLast('.'),
            summary = metaSummary ?: "",
            description = metaDesc ?: "",
            iconUrl = iconUrl,
            featureGraphic = featureGraphic,
            screenshots = screenshots,
            antiFeatures = antiFeatures,
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

    /**
     * index-v2 `screenshots`: `{ phone: { "en-US": [ { name, sha256, size }, … ] }, … }`.
     *
     * Only the phone set is taken — the tablet, TV and wear sets are the same app shot on
     * hardware the reader isn't holding, and mixing them makes the carousel jump between
     * aspect ratios. Falls back to whichever set exists if there is no phone one.
     */
    private fun readScreenshotsV2(reader: JsonReader, repoBase: String): List<String> {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return emptyList()
        }
        var phone: List<String> = emptyList()
        var fallback: List<String> = emptyList()
        reader.beginObject()
        while (reader.hasNext()) {
            val kind = reader.nextName()
            val shots = readLocalizedFileList(reader, repoBase)
            when {
                kind == "phone" -> phone = shots
                fallback.isEmpty() -> fallback = shots
            }
        }
        reader.endObject()
        return phone.ifEmpty { fallback }
    }

    /** `{ "en-US": [ { "name": "/pkg/en-US/phoneScreenshots/1.png" }, … ], … }`. */
    private fun readLocalizedFileList(reader: JsonReader, repoBase: String): List<String> {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return emptyList()
        }
        var enUs: List<String>? = null
        var en: List<String>? = null
        var first: List<String>? = null
        reader.beginObject()
        while (reader.hasNext()) {
            val locale = reader.nextName()
            val names = readFileNameArray(reader).map { repoBase + "/" + it.trimStart('/') }
            if (first == null) first = names
            when (locale) {
                "en-US" -> enUs = names
                "en" -> en = names
            }
        }
        reader.endObject()
        return enUs ?: en ?: first ?: emptyList()
    }

    /** `[ { "name": …, "sha256": …, "size": … }, … ]` reduced to the names. */
    private fun readFileNameArray(reader: JsonReader): List<String> {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return emptyList()
        }
        val names = mutableListOf<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "name") {
                    nextStringOrNull(reader)?.let { names.add(it) }
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
        }
        reader.endArray()
        return names
    }

    /** Keys of an object whose values are of no interest, e.g. v2's anti-feature map. */
    private fun readObjectKeys(reader: JsonReader): List<String> {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return emptyList()
        }
        val keys = mutableListOf<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            keys.add(reader.nextName())
            reader.skipValue()
        }
        reader.endObject()
        return keys
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
