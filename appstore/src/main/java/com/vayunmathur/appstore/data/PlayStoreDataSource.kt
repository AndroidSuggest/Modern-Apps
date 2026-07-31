package com.vayunmathur.appstore.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object PlayStoreDataSource {

    const val PLAY_BASE = "https://play.google.com"

    suspend fun search(query: String): List<UnifiedApp> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = "$PLAY_BASE/store/search?q=$q&c=apps"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            }
            val html = conn.inputStream.bufferedReader().readText()
            AppProvider.filterTargetSdk(parseSearchHtml(html))
        } catch (_: Exception) { emptyList() }
    }

    suspend fun topCharts(category: String = ""): List<UnifiedApp> = withContext(Dispatchers.IO) {
        try {
            val catPath = if (category.isNotBlank()) "/category/${URLEncoder.encode(category, "UTF-8")}" else ""
            val url = "$PLAY_BASE/store/apps$catPath?hl=en_US&gl=US"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
            }
            val html = conn.inputStream.bufferedReader().readText()
            AppProvider.filterTargetSdk(parseSearchHtml(html).take(30))
        } catch (_: Exception) { emptyList() }
    }

    suspend fun appDetails(packageName: String): UnifiedApp? = withContext(Dispatchers.IO) {
        try {
            val url = "$PLAY_BASE/store/apps/details?id=$packageName&hl=en_US&gl=US"
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                .timeout(20000)
                .get()

            val name = doc.selectFirst("h1 span")?.text()
                ?: doc.selectFirst("[itemprop=name] span")?.text()
                ?: packageName.substringAfterLast('.')

            val developer = doc.selectFirst("a[href*=/store/apps/developer] span")?.text()
                ?: doc.selectFirst("a[href*=/store/apps/dev] span")?.text()

            val icon = doc.selectFirst("img[alt*=Icon]")?.attr("src")
                ?: doc.selectFirst("img.T75of")?.attr("src")

            val summary = doc.selectFirst("div[itemprop=description]")?.text()?.take(500) ?: ""
            val ratingText = doc.selectFirst("div.TT9eCd")?.text()
            val rating = ratingText?.toFloatOrNull()

            val pageText = doc.text()
            val targetSdk = Regex("Target.*API.*?(\\d{2})|targetSdk.*?(\\d{2})", RegexOption.IGNORE_CASE)
                .find(pageText)?.groupValues?.filter { it.toIntOrNull() != null }?.lastOrNull()?.toIntOrNull()

            val app = UnifiedApp(
                packageName = packageName,
                source = AppSource.PLAYSTORE,
                name = name,
                summary = summary.take(200),
                description = doc.selectFirst("div[itemprop=description]")?.text() ?: summary,
                iconUrl = icon,
                author = developer,
                categories = emptyList(),
                rating = rating,
                targetSdk = targetSdk,
                website = "$PLAY_BASE/store/apps/details?id=$packageName"
            )
            if (targetSdk != null && targetSdk < AppProvider.MIN_TARGET_SDK) null else app
        } catch (_: Exception) { null }
    }

    private fun parseSearchHtml(html: String): List<UnifiedApp> {
        val results = mutableListOf<UnifiedApp>()
        try {
            val doc = Jsoup.parse(html)
            val candidates = doc.select("a[href*=/store/apps/details?id=]")
            val seen = mutableSetOf<String>()
            for (a in candidates) {
                val href = a.attr("href")
                val pkg = Regex("[?&]id=([^&]+)").find(href)?.groupValues?.get(1) ?: continue
                if (!seen.add(pkg)) continue
                if (results.size >= 40) break
                val title = a.attr("title").ifBlank { a.text().ifBlank { pkg } }
                val img = a.selectFirst("img")?.attr("src")
                    ?: a.parent()?.selectFirst("img")?.attr("src")
                results += UnifiedApp(
                    packageName = pkg,
                    source = AppSource.PLAYSTORE,
                    name = title.take(80),
                    iconUrl = img,
                    summary = "",
                    website = "$PLAY_BASE/store/apps/details?id=$pkg"
                )
            }
        } catch (_: Exception) { }
        return results
    }

    fun playStoreUrl(pkg: String): String = "$PLAY_BASE/store/apps/details?id=$pkg"
}
