package com.vayunmathur.appstore.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * F-Droid index v2 client — Droid-ify style. Fetches index-v2.json then v1.
 * Filters targetSdk < AppProvider.MIN_TARGET_SDK when manifest provides it.
 */
object FDroidRepository {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetchRepoIndex(repoUrl: String): List<UnifiedApp> = withContext(Dispatchers.IO) {
        val base = repoUrl.trimEnd('/')
        val urls = listOf("$base/index-v2.json", "$base/index-v1.json")
        var lastErr: Exception? = null
        for (url in urls) {
            try {
                val apps = if (url.endsWith("v2.json")) fetchV2(url, base) else fetchV1(url, base)
                // Filter targetSdk < 35 for both sources requirement
                return@withContext AppProvider.filterTargetSdk(apps)
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: RuntimeException("Failed to fetch $repoUrl")
    }

    private fun fetchV2(url: String, repoBase: String): List<UnifiedApp> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 60000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ModernAppStore/1.0")
        }
        val text = conn.inputStream.bufferedReader().readText()
        val root = json.parseToJsonElement(text).jsonObject
        val packages = root["packages"]?.jsonObject ?: return emptyList()
        val result = mutableListOf<UnifiedApp>()

        for ((pkg, pkgEl) in packages) {
            try {
                val pkgObj = pkgEl.jsonObject
                val meta = pkgObj["metadata"]?.jsonObject ?: continue
                val versions = pkgObj["versions"]?.jsonObject ?: continue
                if (versions.isEmpty()) continue

                val latestEntry = versions.values.mapNotNull { it.jsonObject }
                    .maxByOrNull { it["added"]?.jsonPrimitive?.longOrNull ?: 0L } ?: continue

                val fileObj = latestEntry["file"]?.jsonObject
                val manifest = latestEntry["manifest"]?.jsonObject
                val usesSdk = manifest?.get("usesSdk")?.jsonObject

                val name = extractLocalized(meta["name"]) ?: pkg.substringAfterLast('.')
                val summary = extractLocalized(meta["summary"]) ?: ""
                val desc = extractLocalized(meta["description"]) ?: ""
                val author = meta["authorName"]?.jsonPrimitive?.contentOrNull
                val cats = meta["categories"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                val versionName = manifest?.get("versionName")?.jsonPrimitive?.contentOrNull
                val versionCode = manifest?.get("versionCode")?.jsonPrimitive?.longOrNull ?: 0L
                val sizeBytes = fileObj?.get("size")?.jsonPrimitive?.longOrNull ?: 0L
                val apkName = fileObj?.get("name")?.jsonPrimitive?.contentOrNull
                val apkUrl = if (apkName != null) "$repoBase/$apkName" else null
                val website = meta["webSite"]?.jsonPrimitive?.contentOrNull
                val source = meta["sourceCode"]?.jsonPrimitive?.contentOrNull
                val license = meta["license"]?.jsonPrimitive?.contentOrNull
                val whatsNew = extractLocalized(latestEntry["whatsNew"])
                val added = meta["added"]?.jsonPrimitive?.longOrNull ?: 0L
                val lastUpdated = meta["lastUpdated"]?.jsonPrimitive?.longOrNull ?: added
                val targetSdk = usesSdk?.get("targetSdkVersion")?.jsonPrimitive?.contentOrNull?.toIntOrNull()

                val iconName = runCatching {
                    meta["icon"]?.jsonObject?.values?.firstOrNull()
                        ?.jsonObject?.values?.firstOrNull()
                        ?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                val iconUrl = iconName?.let { "$repoBase/icons/$it" }

                result += UnifiedApp(
                    packageName = pkg,
                    source = AppSource.FDROID,
                    name = name,
                    summary = summary,
                    description = desc,
                    iconUrl = iconUrl,
                    author = author,
                    categories = cats,
                    versionName = versionName,
                    versionCode = versionCode,
                    sizeBytes = sizeBytes,
                    apkUrl = apkUrl,
                    targetSdk = targetSdk,
                    license = license,
                    website = website,
                    sourceCode = source,
                    whatsNew = whatsNew,
                    addedTimestamp = added,
                    lastUpdated = lastUpdated,
                    repoUrl = repoBase
                )
            } catch (_: Exception) { }
        }
        return result
    }

    private fun fetchV1(url: String, repoBase: String): List<UnifiedApp> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 60000
            setRequestProperty("User-Agent", "ModernAppStore/1.0")
        }
        val text = conn.inputStream.bufferedReader().readText()
        val root = json.parseToJsonElement(text).jsonObject
        val apps = root["apps"]?.jsonArray ?: return emptyList()
        val packages = root["packages"]?.jsonObject

        val result = mutableListOf<UnifiedApp>()
        for (el in apps) {
            try {
                val obj = el.jsonObject
                val pkg = obj["packageName"]?.jsonPrimitive?.contentOrNull ?: continue
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: pkg
                val summary = obj["summary"]?.jsonPrimitive?.contentOrNull ?: ""
                val desc = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
                val author = obj["authorName"]?.jsonPrimitive?.contentOrNull
                val cats = obj["categories"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                val website = obj["webSite"]?.jsonPrimitive?.contentOrNull
                val src = obj["sourceCode"]?.jsonPrimitive?.contentOrNull
                val license = obj["license"]?.jsonPrimitive?.contentOrNull
                val added = obj["added"]?.jsonPrimitive?.longOrNull ?: 0L
                val lastUpdated = obj["lastUpdated"]?.jsonPrimitive?.longOrNull ?: added
                val iconName = obj["icon"]?.jsonPrimitive?.contentOrNull
                val iconUrl = iconName?.let { "$repoBase/icons/$it" }

                val versions = packages?.get(pkg)?.jsonArray
                val latestVer = versions?.lastOrNull()?.jsonObject
                val apkName = latestVer?.get("apkName")?.jsonPrimitive?.contentOrNull
                val apkUrl = apkName?.let { "$repoBase/$it" }
                val vName = latestVer?.get("versionName")?.jsonPrimitive?.contentOrNull
                val vCode = latestVer?.get("versionCode")?.jsonPrimitive?.longOrNull ?: 0L
                val size = latestVer?.get("size")?.jsonPrimitive?.longOrNull ?: 0L
                val targetSdk = latestVer?.get("targetSdkVersion")?.jsonPrimitive?.contentOrNull?.toIntOrNull()

                result += UnifiedApp(
                    packageName = pkg,
                    source = AppSource.FDROID,
                    name = name,
                    summary = summary,
                    description = desc,
                    iconUrl = iconUrl,
                    author = author,
                    categories = cats,
                    versionName = vName,
                    versionCode = vCode,
                    sizeBytes = size,
                    apkUrl = apkUrl,
                    targetSdk = targetSdk,
                    website = website,
                    sourceCode = src,
                    license = license,
                    addedTimestamp = added,
                    lastUpdated = lastUpdated,
                    repoUrl = repoBase
                )
            } catch (_: Exception) { }
        }
        return result
    }

    private fun extractLocalized(elem: kotlinx.serialization.json.JsonElement?): String? {
        if (elem == null) return null
        if (elem is kotlinx.serialization.json.JsonPrimitive) return elem.contentOrNull
        val obj = elem as? JsonObject ?: return null
        return obj["en-US"]?.jsonPrimitive?.contentOrNull
            ?: obj["en"]?.jsonPrimitive?.contentOrNull
            ?: obj.values.firstOrNull()?.jsonPrimitive?.contentOrNull
    }
}
