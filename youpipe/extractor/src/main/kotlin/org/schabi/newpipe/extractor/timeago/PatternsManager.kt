package org.schabi.newpipe.extractor.timeago

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.time.temporal.ChronoUnit
import java.util.Collections
import javax.annotation.Nonnull
import javax.annotation.Nullable

object PatternsManager {
    private const val PATTERNS_RESOURCE = "/unique_patterns.json"

    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var patternMap: Map<String, PatternsHolder>? = null

    @JvmStatic
    @Nullable
    fun getPatterns(
        @Nonnull languageCode: String,
        @Nullable countryCode: String?
    ): PatternsHolder? {
        val targetLocalizationClassName = languageCode +
            if (countryCode == null || countryCode.isEmpty()) "" else "_$countryCode"
        return getPatternMap()[targetLocalizationClassName]
    }

    private fun getPatternMap(): Map<String, PatternsHolder> {
        var map = patternMap
        if (map == null) {
            synchronized(PatternsManager::class.java) {
                map = patternMap
                if (map == null) {
                    map = loadPatterns()
                    patternMap = map
                }
            }
        }
        return map!!
    }

    private fun loadPatterns(): Map<String, PatternsHolder> {
        val map = mutableMapOf<String, PatternsHolder>()
        try {
            val inputStream = PatternsManager::class.java.getResourceAsStream(PATTERNS_RESOURCE)
                ?: throw IllegalStateException(
                    "Could not find time ago patterns resource $PATTERNS_RESOURCE"
                )
            inputStream.use { stream ->
                val text = stream.readBytes().decodeToString()
                val root = jsonParser.parseToJsonElement(text).jsonObject
                for ((key, value) in root.entries) {
                    map[key] = holderFrom(value.jsonObject)
                }
            }
        } catch (e: IOException) {
            throw IllegalStateException("Could not load time ago patterns", e)
        } catch (e: Exception) {
            if (e is IllegalStateException) throw e
            throw IllegalStateException("Could not load time ago patterns", e)
        }
        return Collections.unmodifiableMap(map)
    }

    private fun holderFrom(value: kotlinx.serialization.json.JsonObject): PatternsHolder {
        val units = UNIT_KEYS.associateWith { (_, key) -> patternsFor(value[key]) }

        val holder = PatternsHolder(
            wordSeparator = value["word_separator"]?.jsonPrimitive?.contentOrNull ?: "",
            seconds = units.forKey("seconds"),
            minutes = units.forKey("minutes"),
            hours = units.forKey("hours"),
            days = units.forKey("days"),
            weeks = units.forKey("weeks"),
            months = units.forKey("months"),
            years = units.forKey("years")
        )

        for ((unitAndKey, patterns) in units) {
            val (unit, _) = unitAndKey
            for ((caseText, caseAmount) in patterns.specialCases) {
                holder.putSpecialCase(unit, caseText, caseAmount)
            }
        }
        return holder
    }

    private fun Map<Pair<ChronoUnit, String>, Patterns>.forKey(key: String): Collection<String> =
        entries.first { it.key.second == key }.value.words

    /**
     * Splits one unit's array into plain words and special cases.
     *
     * Entries are normally strings, but a locale may also supply an object mapping an amount to a
     * phrase that names it — Hebrew's dual forms, e.g. `{"2": "שעתיים"}` ("two hours"), which
     * contain no digits for [org.schabi.newpipe.extractor.localization.TimeAgoParser] to read.
     * Treating those as strings threw and, because the whole file is loaded eagerly, broke time
     * parsing for *every* locale rather than just Hebrew.
     */
    private fun patternsFor(element: JsonElement?): Patterns {
        val array = element as? JsonArray ?: return Patterns(emptyList(), emptyMap())
        val words = mutableListOf<String>()
        val specialCases = mutableMapOf<String, Int>()

        for (entry in array) {
            when (entry) {
                is JsonPrimitive -> entry.contentOrNull?.let { words.add(it) }
                is kotlinx.serialization.json.JsonObject ->
                    for ((amount, text) in entry) {
                        val caseAmount = amount.toIntOrNull() ?: continue
                        val caseText = (text as? JsonPrimitive)?.contentOrNull ?: continue
                        specialCases[caseText] = caseAmount
                        // Also a matchable word, so the unit is still recognised in the phrase.
                        words.add(caseText)
                    }
                // Anything else is a shape we do not know; skip rather than fail the locale.
                else -> Unit
            }
        }
        return Patterns(words, specialCases)
    }

    private class Patterns(val words: Collection<String>, val specialCases: Map<String, Int>)

    private val UNIT_KEYS = listOf(
        ChronoUnit.SECONDS to "seconds",
        ChronoUnit.MINUTES to "minutes",
        ChronoUnit.HOURS to "hours",
        ChronoUnit.DAYS to "days",
        ChronoUnit.WEEKS to "weeks",
        ChronoUnit.MONTHS to "months",
        ChronoUnit.YEARS to "years",
    )
}
