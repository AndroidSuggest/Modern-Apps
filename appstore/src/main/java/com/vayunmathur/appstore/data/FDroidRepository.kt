package com.vayunmathur.appstore.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fixed F-Droid repo client — avoids OOM on 99MB index-v2.json.
 * Downloads to file first (buffered), then streaming-parses with android.util.JsonReader
 * so we never hold the full String + JsonObject tree in memory.
 * Filters targetSdk < AppProvider.MIN_TARGET_SDK.
 */
object FDroidRepository {

    suspend fun fetchRepoIndex(context: Context, repoUrl: String): List<UnifiedApp> = withContext(Dispatchers.IO) {
        val base = repoUrl.trimEnd('/')
        val cacheFile = File(context.cacheDir, "fdroid_${base.hashCode()}_index.json")
        val candidates = listOf("$base/index-v2.json", "$base/index-v1.json")
        var lastErr: Exception? = null
        for (url in candidates) {
            try {
                downloadToFile(url, cacheFile)
                val apps = if (url.endsWith("v2.json")) parseV2Streaming(cacheFile, base) else parseV1Streaming(cacheFile, base)
                val filtered = AppProvider.filterTargetSdk(apps)
                cacheFile.delete()
                return@withContext filtered
            } catch (e: Exception) {
                lastErr = e
            }
        }
        cacheFile.delete()
        throw lastErr ?: RuntimeException("Failed to fetch $repoUrl")
    }

    suspend fun fetchRepoIndex(repoUrl: String): List<UnifiedApp> = withContext(Dispatchers.IO) {
        val base = repoUrl.trimEnd('/')
        val temp = File.createTempFile("fdroid_index_", ".json")
        try {
            val candidates = listOf("$base/index-v2.json", "$base/index-v1.json")
            var lastErr: Exception? = null
            for (url in candidates) {
                try {
                    downloadToFile(url, temp)
                    val apps = if (url.endsWith("v2.json")) parseV2Streaming(temp, base) else parseV1Streaming(temp, base)
                    return@withContext AppProvider.filterTargetSdk(apps)
                } catch (e: Exception) {
                    lastErr = e
                }
            }
            throw lastErr ?: RuntimeException("Failed to fetch $repoUrl")
        } finally {
            temp.delete()
        }
    }

    private fun downloadToFile(url: String, outFile: File) {
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

    private fun parseV2Streaming(file: File, repoBase: String): List<UnifiedApp> {
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
                                val app = parsePackageV2(r, pkg, repoBase)
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

    private fun parsePackageV2(reader: JsonReader, packageName: String, repoBase: String): UnifiedApp? {
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
                                val iconName = readIconName(reader)
                                if (iconName != null) iconUrl = "$repoBase/icons/$iconName"
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
                            if (vAdded >= latestAdded) {
                                latestAdded = vAdded
                                latestFileName = vFileName
                                latestSize = vFileSize
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

        val apkUrl = latestFileName?.let { "$repoBase/$it" }
        return UnifiedApp(
            packageName = packageName,
            source = AppSource.FDROID,
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

    private fun parseV1Streaming(file: File, repoBase: String): List<UnifiedApp> {
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
                                val latest = parseV1PackagesArray(r)
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
            val latest = packagesMap[pkg]
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
                    apkUrl = latest?.apkName?.let { "$repoBase/$it" },
                    targetSdk = latest?.targetSdk,
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
        val targetSdk: Int?
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

    private fun parseV1PackagesArray(reader: JsonReader): V1PackageLatest? {
        // array of package versions, take last
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
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "apkName" -> apkName = nextStringOrNull(reader)
                        "versionName" -> versionName = nextStringOrNull(reader)
                        "versionCode" -> versionCode = nextLongOrNull(reader) ?: 0L
                        "size" -> size = nextLongOrNull(reader) ?: 0L
                        "targetSdkVersion" -> targetSdk = nextIntOrNull(reader)
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                last = V1PackageLatest(apkName, versionName, versionCode, size, targetSdk)
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
