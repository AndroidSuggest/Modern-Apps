package org.schabi.newpipe.extractor.timeago

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
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
        return PatternsHolder(
            wordSeparator = value["word_separator"]?.jsonPrimitive?.contentOrNull ?: "",
            seconds = stringList(value["seconds"]?.jsonArray),
            minutes = stringList(value["minutes"]?.jsonArray),
            hours = stringList(value["hours"]?.jsonArray),
            days = stringList(value["days"]?.jsonArray),
            weeks = stringList(value["weeks"]?.jsonArray),
            months = stringList(value["months"]?.jsonArray),
            years = stringList(value["years"]?.jsonArray)
        )
    }

    private fun stringList(array: JsonArray?): Collection<String> {
        if (array == null) return emptyList()
        return array.map { it.jsonPrimitive.content }
    }
}
