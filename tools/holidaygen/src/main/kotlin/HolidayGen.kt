import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration
import java.util.regex.Pattern

/**
 * Generates the calendar app's bundled holiday data from Google's public
 * iCal holiday feeds (en.<slug>#holiday@group.v.calendar.google.com).
 *
 * Kotlin port of HolidayGen.java — identical runtime behavior.
 */
private const val URL_TEMPLATE =
    "https://calendar.google.com/calendar/ical/%s.%s%%23holiday%%40group.v.calendar.google.com/public/basic.ics"

// 67 Google-supported language prefixes with display names
private val LANGS = arrayOf(
    arrayOf("en", "English"), arrayOf("es", "Spanish"), arrayOf("fr", "French"),
    arrayOf("de", "German"), arrayOf("it", "Italian"), arrayOf("pt", "Portuguese"),
    arrayOf("ru", "Russian"), arrayOf("zh", "Chinese"), arrayOf("ja", "Japanese"),
    arrayOf("ko", "Korean"), arrayOf("ar", "Arabic"), arrayOf("hi", "Hindi"),
    arrayOf("nl", "Dutch"), arrayOf("pl", "Polish"), arrayOf("tr", "Turkish"),
    arrayOf("sv", "Swedish"), arrayOf("da", "Danish"), arrayOf("no", "Norwegian"),
    arrayOf("fi", "Finnish"), arrayOf("cs", "Czech"), arrayOf("hu", "Hungarian"),
    arrayOf("el", "Greek"), arrayOf("he", "Hebrew"), arrayOf("th", "Thai"),
    arrayOf("vi", "Vietnamese"), arrayOf("id", "Indonesian"), arrayOf("ms", "Malay"),
    arrayOf("uk", "Ukrainian"), arrayOf("ro", "Romanian"), arrayOf("bg", "Bulgarian"),
    arrayOf("hr", "Croatian"), arrayOf("sk", "Slovak"), arrayOf("sl", "Slovenian"),
    arrayOf("lt", "Lithuanian"), arrayOf("lv", "Latvian"), arrayOf("et", "Estonian"),
    arrayOf("sr", "Serbian"), arrayOf("ca", "Catalan"), arrayOf("eu", "Basque"),
    arrayOf("gl", "Galician"), arrayOf("is", "Icelandic"), arrayOf("ga", "Irish"),
    arrayOf("mt", "Maltese"), arrayOf("cy", "Welsh"), arrayOf("af", "Afrikaans"),
    arrayOf("sq", "Albanian"), arrayOf("hy", "Armenian"), arrayOf("az", "Azerbaijani"),
    arrayOf("be", "Belarusian"), arrayOf("bn", "Bengali"), arrayOf("bs", "Bosnian"),
    arrayOf("ka", "Georgian"), arrayOf("km", "Khmer"), arrayOf("kn", "Kannada"),
    arrayOf("ky", "Kyrgyz"), arrayOf("lo", "Lao"), arrayOf("mk", "Macedonian"),
    arrayOf("ml", "Malayalam"), arrayOf("mn", "Mongolian"), arrayOf("my", "Burmese"),
    arrayOf("ne", "Nepali"), arrayOf("pa", "Punjabi"), arrayOf("si", "Sinhala"),
    arrayOf("ta", "Tamil"), arrayOf("te", "Telugu"), arrayOf("ur", "Urdu"),
    arrayOf("uz", "Uzbek"),
)

// Verified Google holiday-feed slugs
private val SLUGS = arrayOf(
    "usa", "uk", "canadian", "australian", "indian", "irish", "french", "german",
    "italian", "spain", "portuguese", "dutch", "danish", "finnish", "norwegian", "swedish",
    "polish", "russian", "ukrainian", "austrian", "bulgarian", "croatian", "czech", "greek",
    "hungarian", "latvian", "lithuanian", "romanian", "slovak", "slovenian", "turkish", "japanese",
    "china", "taiwan", "hong_kong", "south_korea", "singapore", "indonesian", "malaysia", "philippines",
    "vietnamese", "brazilian", "mexican", "new_zealand", "jewish", "christian", "islamic", "judaism",
    "hinduism", "orthodox_christianity", "sa", "ar", "cl", "co", "pe", "th",
    "be", "ch", "lu", "is", "rs", "eg", "pk", "bd",
    "af", "al", "dz", "ad", "ao", "ag", "am", "az",
    "bs", "bh", "bb", "by", "bz", "bj", "bt", "bo",
    "ba", "bw", "bn", "bf", "bi", "cv", "kh", "cm",
    "cf", "td", "km", "cg", "cd", "cr", "ci", "cu",
    "cy", "dj", "dm", "do", "ec", "sv", "gq", "er",
    "ee", "sz", "et", "fj", "ga", "gm", "ge", "gh",
    "gd", "gt", "gn", "gw", "gy", "ht", "hn", "ir",
    "iq", "jm", "jo", "kz", "ke", "ki", "kw", "kg",
    "la", "lb", "ls", "lr", "ly", "li", "mg", "mw",
    "mv", "ml", "mt", "mh", "mr", "mu", "fm", "md",
    "mc", "mn", "me", "ma", "mz", "mm", "na", "nr",
    "np", "ni", "ne", "ng", "mk", "om", "pw", "pa",
    "pg", "py", "qa", "rw", "kn", "lc", "vc", "ws",
    "sm", "st", "sn", "sc", "sl", "sb", "so", "ss",
    "lk", "sd", "sr", "sy", "tj", "tz", "tl", "tg",
    "to", "tt", "tn", "tm", "tv", "ug", "ae", "uy",
    "uz", "vu", "ve", "ye", "zm", "zw", "mo", "pr",
    "gu", "vi",
)

private val HTTP = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL)
    .connectTimeout(Duration.ofSeconds(30))
    .build()

fun main(args: Array<String>) {
    val startSlug = args.firstOrNull()
    val baseDir = File("calendar/src/main/assets/holidays")
    val resume = startSlug != null && baseDir.exists()

    if (!resume) {
        baseDir.deleteRecursively()
    }
    if (!baseDir.exists() && !baseDir.mkdirs()) {
        throw IllegalStateException("Could not create ${baseDir.absolutePath}")
    }
    if (resume) {
        println("Resuming from slug: $startSlug")
    }

    // Write languages.json at top level
    val langsJson = buildString {
        append("[")
        LANGS.forEachIndexed { i, pair ->
            if (i > 0) append(',')
            append("{\"code\":\"${pair[0]}\",\"name\":${jsonString(pair[1])}}")
        }
        append("]")
    }
    Files.write(
        File(baseDir, "languages.json").toPath(),
        langsJson.toByteArray(StandardCharsets.UTF_8)
    )

    // Phase 1: Build canonical slug -> code mapping from English feed
    println("Phase 1/2: Building canonical index from English...")
    val slugToCode = mutableMapOf<String, String>()
    val codeToName = mutableMapOf<String, String>()
    val canonicalIndex = mutableListOf<Array<String>>()

    for (i in SLUGS.indices) {
        val slug = SLUGS[i]
        printProgress("  Fetching English", i + 1, SLUGS.size)
        try {
            val ics = get(String.format(URL_TEMPLATE, "en", slug))
            val lines = unfold(ics)
            val name = calendarName(lines) ?: continue
            val code = name.replace(Regex("[^A-Za-z0-9]"), "")
            slugToCode[slug] = code
            codeToName[code] = name
            canonicalIndex.add(arrayOf(code, name))
        } catch (ex: Exception) {
            System.err.println("\n  [en] Skipping $slug: ${ex.message}")
        }
    }
    println()
    canonicalIndex.sortWith { a, b -> a[1].compareTo(b[1], ignoreCase = true) }
    println("  \u2713 Canonical: ${canonicalIndex.size} countries found\n")

    // Write top-level index.json
    val topIdx = buildString {
        append("[")
        canonicalIndex.forEachIndexed { i, entry ->
            if (i > 0) append(',')
            append("{\"code\":\"${entry[0]}\",\"name\":${jsonString(entry[1])}}")
        }
        append("]")
    }
    Files.write(
        File(baseDir, "index.json").toPath(),
        topIdx.toByteArray(StandardCharsets.UTF_8)
    )

    // Phase 2: For each slug, fetch English first, then other langs
    println("Phase 2/2: Fetching localized holidays (comparing to English, only saving differences)...")
    val countryLangs = mutableMapOf<String, MutableSet<String>>()

    if (resume) {
        val clFile = File(baseDir, "country_languages.json")
        if (clFile.exists()) {
            try {
                val clText = String(Files.readAllBytes(clFile.toPath()), StandardCharsets.UTF_8)
                val m = Pattern.compile("\"([^\"]+)\":\\[([^\\]]*)\\]").matcher(clText)
                while (m.find()) {
                    val c = m.group(1)
                    val langs = m.group(2).replace("\"", "").split(",")
                    val set = mutableSetOf<String>()
                    for (lang in langs) if (lang.trim().isNotEmpty()) set.add(lang.trim())
                    if (set.isNotEmpty()) countryLangs[c] = set
                }
                println("  Loaded existing progress for ${countryLangs.size} countries")
            } catch (_: Exception) { /* ignore */ }
        }
    }

    var totalWritten = 0
    val totalCountries = slugToCode.size
    var countryIdx = 0
    val totalLangs = LANGS.size - 1
    var started = (startSlug == null)

    for (slug in SLUGS) {
        if (!started) {
            if (slug == startSlug) started = true
            else continue
        }
        val code = slugToCode[slug] ?: continue
        countryIdx++

        // Fetch English baseline
        val enHolidays: List<Array<String>>
        try {
            val enIcs = get(String.format(URL_TEMPLATE, "en", slug))
            enHolidays = parseEvents(unfold(enIcs))
            if (enHolidays.isEmpty()) continue
            (enHolidays as MutableList).sortWith { a, b ->
                val c = a[0].compareTo(b[0])
                if (c != 0) c else a[1].compareTo(b[1], ignoreCase = true)
            }
        } catch (ex: Exception) {
            System.err.println("\n  [en] Skipping $slug: ${ex.message}")
            continue
        }

        // Write English
        val enDir = File(baseDir, "en")
        enDir.mkdirs()
        writeHolidays(File(enDir, "$code.json"), enHolidays)
        countryLangs.getOrPut(code) { mutableSetOf() }.add("en")
        totalWritten++

        // Fetch other languages, compare to English
        var langIdx = 0
        var distinctCount = 1
        for (langPair in LANGS) {
            val lang = langPair[0]
            if (lang == "en") continue
            langIdx++
            printProgress(
                "  [$countryIdx/$totalCountries] $code ($distinctCount langs)",
                langIdx, totalLangs
            )
            try {
                val ics = get(String.format(URL_TEMPLATE, lang, slug))
                val holidays = parseEvents(unfold(ics))
                if (holidays.isEmpty()) continue
                (holidays as MutableList).sortWith { a, b ->
                    val c = a[0].compareTo(b[0])
                    if (c != 0) c else a[1].compareTo(b[1], ignoreCase = true)
                }

                if (holidaysEqual(enHolidays, holidays)) continue

                val langDir = File(baseDir, lang)
                langDir.mkdirs()
                writeHolidays(File(langDir, "$code.json"), holidays)
                countryLangs.getOrPut(code) { mutableSetOf() }.add(lang)
                distinctCount++
                totalWritten++
            } catch (_: Exception) {
                // skip silently
            }
        }
    }
    println()

    // Write per-language index.json
    for (langPair in LANGS) {
        val lang = langPair[0]
        val langDir = File(baseDir, lang)
        if (!langDir.exists()) continue
        val index = mutableListOf<Array<String>>()
        for (entry in canonicalIndex) {
            if (File(langDir, "${entry[0]}.json").exists()) {
                index.add(arrayOf(entry[0], entry[1]))
            }
        }
        index.sortWith { a, b -> a[1].compareTo(b[1], ignoreCase = true) }
        val idx = buildString {
            append("[")
            index.forEachIndexed { i, e ->
                if (i > 0) append(',')
                append("{\"code\":\"${e[0]}\",\"name\":${jsonString(e[1])}}")
            }
            append("]")
        }
        Files.write(
            File(langDir, "index.json").toPath(),
            idx.toByteArray(StandardCharsets.UTF_8)
        )
    }

    // Copy English to flat structure for backward compat
    val enDir = File(baseDir, "en")
    val enFiles = enDir.listFiles { _, n -> n.endsWith(".json") && n != "index.json" }
    enFiles?.forEach { src ->
        Files.copy(src.toPath(), File(baseDir, src.name).toPath())
    }

    // Write country_languages.json
    val cl = buildString {
        append("{")
        var first = true
        for ((key, langs) in countryLangs) {
            if (!first) append(',')
            first = false
            append("\"$key\":[")
            var firstLang = true
            for (lc in langs) {
                if (!firstLang) append(',')
                firstLang = false
                append("\"$lc\"")
            }
            append("]")
        }
        append("}")
    }
    Files.write(
        File(baseDir, "country_languages.json").toPath(),
        cl.toByteArray(StandardCharsets.UTF_8)
    )

    println("  \u2713 Wrote $totalWritten files across ${LANGS.size} languages to ${baseDir.absolutePath}")
}

private fun printProgress(label: String, current: Int, total: Int) {
    val width = 30
    val filled = (current.toDouble() / total * width).toInt()
    val bar = buildString {
        append("\r$label [")
        for (i in 0 until width) append(if (i < filled) "\u2588" else "\u2591")
        append("] $current/$total (${"%.0f".format(current.toDouble() / total * 100)}%)")
    }
    print(bar)
    if (current == total) println()
    System.out.flush()
}

private fun holidaysEqual(a: List<Array<String>>, b: List<Array<String>>): Boolean {
    if (a.size != b.size) return false
    for (i in a.indices) {
        if (a[i][0] != b[i][0] || a[i][1] != b[i][1]) return false
    }
    return true
}

private fun writeHolidays(out: File, holidays: List<Array<String>>) {
    val sb = buildString {
        append("[")
        holidays.forEachIndexed { i, h ->
            if (i > 0) append(',')
            append("{\"d\":\"${h[0]}\",\"n\":${jsonString(h[1])}}")
        }
        append("]")
    }
    Files.write(out.toPath(), sb.toByteArray(StandardCharsets.UTF_8))
}

private fun calendarName(lines: List<String>): String? {
    for (line in lines) {
        if (line.startsWith("X-WR-CALNAME:")) {
            return line.substring("X-WR-CALNAME:".length)
                .replace(Regex("^Holidays and Observances in "), "")
                .replace(Regex("^Holidays in "), "")
                .trim()
        }
    }
    return null
}

private fun parseEvents(lines: List<String>): List<Array<String>> {
    val out = mutableListOf<Array<String>>()
    var inEvent = false
    var summary: String? = null
    var date: String? = null
    for (line in lines) {
        when {
            line == "BEGIN:VEVENT" -> { inEvent = true; summary = null; date = null }
            line == "END:VEVENT" -> {
                if (summary != null && date != null) out.add(arrayOf(date!!, summary!!))
                inEvent = false
            }
            !inEvent -> continue
            else -> {
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val left = line.substring(0, colon).uppercase()
                val value = line.substring(colon + 1)
                when {
                    left == "SUMMARY" -> summary = unescape(value)
                    left.startsWith("DTSTART") -> {
                        val d = Pattern.compile("(\\d{4})(\\d{2})(\\d{2})").matcher(value)
                        if (d.find()) date = "${d.group(1)}-${d.group(2)}-${d.group(3)}"
                    }
                }
            }
        }
    }
    return out
}

private fun unfold(ics: String): List<String> {
    val out = mutableListOf<String>()
    for (raw in ics.split(Regex("\\r?\\n"))) {
        if ((raw.startsWith(" ") || raw.startsWith("\t")) && out.isNotEmpty()) {
            out[out.lastIndex] = out.last() + raw.substring(1)
        } else {
            out.add(raw)
        }
    }
    return out
}

private fun unescape(s: String): String {
    val b = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            val n = s[++i]
            when (n) {
                'n', 'N' -> b.append(' ')
                ',' -> b.append(',')
                ';' -> b.append(';')
                '\\' -> b.append('\\')
                else -> b.append(n)
            }
        } else {
            b.append(c)
        }
        i++
    }
    return b.toString().trim()
}

private fun get(url: String): String {
    val req = HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", "Mozilla/5.0 (holidaygen)")
        .timeout(Duration.ofSeconds(60))
        .GET().build()
    val resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    if (resp.statusCode() / 100 != 2) {
        throw IllegalStateException("HTTP ${resp.statusCode()}")
    }
    return resp.body()
}

private fun jsonString(s: String): String {
    val b = StringBuilder("\"")
    for (c in s) {
        when (c) {
            '"' -> b.append("\\\"")
            '\\' -> b.append("\\\\")
            '\n' -> b.append("\\n")
            '\r' -> b.append("\\r")
            '\t' -> b.append("\\t")
            else -> if (c.code < 0x20) b.append("\\u%04x".format(c.code))
            else b.append(c)
        }
    }
    return b.append('"').toString()
}
