package org.schabi.newpipe.extractor.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jsoup.Jsoup
import org.schabi.newpipe.extractor.exceptions.ParsingException
import javax.annotation.Nonnull
import javax.annotation.Nullable

private val jsonParser = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

object JsonUtils {

    @JvmStatic
    @Nonnull
    @Throws(ParsingException::class)
    fun getValue(
        @Nonnull obj: JsonObject,
        @Nonnull path: String
    ): JsonElement {
        if (path.isEmpty()) throw ParsingException("Empty path")
        val keys = path.split(".")
        val parentKeys = if (keys.size > 1) keys.subList(0, keys.size - 1) else emptyList()
        val parent: JsonObject = if (parentKeys.isEmpty()) {
            obj
        } else {
            getObjectFromPath(obj, parentKeys)
                ?: throw ParsingException("Unable to get $path")
        }
        return parent[keys.last()] ?: throw ParsingException("Unable to get $path")
    }

    @Nullable
    private fun getObjectFromPath(
        root: JsonObject,
        keys: List<String>
    ): JsonObject? {
        var current: JsonObject = root
        for (key in keys) {
            val element = current[key] ?: return null
            if (element is JsonObject) {
                current = element
            } else {
                return null
            }
        }
        return current
    }

    @JvmStatic
    @Nonnull
    @Throws(ParsingException::class)
    fun getString(obj: JsonObject, path: String): String {
        val value = getValue(obj, path)
        val primitive = value as? JsonPrimitive
            ?: throw ParsingException("Wrong data type at path $path")
        if (!primitive.isString) throw ParsingException("Wrong data type at path $path")
        return primitive.content
    }

    @JvmStatic
    @Nonnull
    @Throws(ParsingException::class)
    fun getBoolean(obj: JsonObject, path: String): Boolean {
        val value = getValue(obj, path)
        val primitive = value as? JsonPrimitive
            ?: throw ParsingException("Wrong data type at path $path")
        return primitive.booleanOrNull
            ?: throw ParsingException("Wrong data type at path $path")
    }

    @JvmStatic
    @Nonnull
    @Throws(ParsingException::class)
    fun getNumber(obj: JsonObject, path: String): Number {
        val value = getValue(obj, path)
        val primitive = value as? JsonPrimitive
            ?: throw ParsingException("Wrong data type at path $path")
        if (primitive.isString) throw ParsingException("Wrong data type at path $path")
        primitive.longOrNull?.let { return it }
        primitive.doubleOrNull?.let { return it }
        // Fallback: try to parse content as Number (handles int, etc.)
        return primitive.content.toDoubleOrNull()
            ?: throw ParsingException("Wrong data type at path $path")
    }

    @JvmStatic
    @Nonnull
    @Throws(ParsingException::class)
    fun getObject(obj: JsonObject, path: String): JsonObject {
        val value = getValue(obj, path)
        if (value is JsonObject) return value
        throw ParsingException("Wrong data type at path $path")
    }

    @JvmStatic
    @Nonnull
    @Throws(ParsingException::class)
    fun getArray(obj: JsonObject, path: String): JsonArray {
        val value = getValue(obj, path)
        if (value is JsonArray) return value
        throw ParsingException("Wrong data type at path $path")
    }

    @JvmStatic
    @Nonnull
    @Throws(ParsingException::class)
    fun getValues(array: JsonArray, path: String): List<JsonElement> {
        val result = mutableListOf<JsonElement>()
        for (element in array) {
            val obj = element as? JsonObject
                ?: throw ParsingException("Array contains non-object at path $path")
            result.add(getValue(obj, path))
        }
        return result
    }

    @JvmStatic
    @Throws(ParsingException::class)
    fun toJsonArray(responseBody: String): JsonArray {
        try {
            return jsonParser.parseToJsonElement(responseBody).jsonArray
        } catch (e: Exception) {
            throw ParsingException("Could not parse JSON", e)
        }
    }

    @JvmStatic
    @Throws(ParsingException::class)
    fun toJsonObject(responseBody: String): JsonObject {
        try {
            return jsonParser.parseToJsonElement(responseBody).jsonObject
        } catch (e: Exception) {
            throw ParsingException("Could not parse JSON", e)
        }
    }

    /**
     * Get an attribute of a web page as JSON.
     *
     * Originally a part of bandcampDirect.
     *
     * Example HTML:
     * ```
     * <p data-town="{"name":"Mycenae","country":"Greece"}">
     * This is Sparta!</p>
     * ```
     * Calling this function to get the attribute `data-town` returns the JsonObject for
     * ```
     * {
     *   "name": "Mycenae",
     *   "country": "Greece"
     * }
     * ```
     *
     * @param html     The HTML where the JSON we're looking for is stored
     * @param variable Name of the variable / attribute
     * @return The JsonObject stored in the variable with this name
     */
    @JvmStatic
    @Throws(ParsingException::class)
    fun getJsonData(html: String, variable: String): JsonObject {
        try {
            val document = Jsoup.parse(html)
            val json = document.getElementsByAttribute(variable).attr(variable)
            if (json.isBlank()) {
                throw ParsingException("Unable to get JSON data for variable $variable")
            }
            return jsonParser.parseToJsonElement(json).jsonObject
        } catch (e: ParsingException) {
            throw e
        } catch (e: Exception) {
            throw ParsingException("Could not parse JSON data for variable $variable", e)
        }
    }

    @JvmStatic
    fun getStringListFromJsonArray(array: JsonArray): List<String> {
        return array.mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
    }
}

// ---------------------------------------------------------------------------
// Compatibility extensions mimicking old nanojson API to ease migration
// ---------------------------------------------------------------------------

fun JsonObject.getString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonObject.getString(key: String, defaultValue: String): String =
    getString(key) ?: defaultValue

fun JsonObject.getString(key: String, defaultValue: String?): String? =
    getString(key) ?: defaultValue

fun JsonObject.getObject(key: String): JsonObject? =
    this[key] as? JsonObject

fun JsonObject.getArray(key: String): JsonArray? =
    this[key] as? JsonArray

fun JsonObject.getBoolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

fun JsonObject.getBoolean(key: String, defaultValue: Boolean): Boolean =
    getBoolean(key) ?: defaultValue

fun JsonObject.getNumber(key: String): Number? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    primitive.longOrNull?.let { return it }
    primitive.doubleOrNull?.let { return it }
    return null
}

fun JsonObject.getInt(key: String): Int? =
    (this[key] as? JsonPrimitive)?.let { p ->
        p.content.toIntOrNull()
            ?: p.longOrNull?.toInt()
            ?: p.doubleOrNull?.toInt()
    }

fun JsonObject.getLong(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull
        ?: (this[key] as? JsonPrimitive)?.doubleOrNull?.toLong()

fun JsonObject.getDouble(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull

fun JsonObject.getValue(key: String): JsonElement? = this[key]

// JsonArray compatibility helpers
fun JsonArray.getString(index: Int): String? =
    (this.getOrNull(index) as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonArray.getString(index: Int, defaultValue: String): String =
    getString(index) ?: defaultValue

fun JsonArray.getObject(index: Int): JsonObject? =
    this.getOrNull(index) as? JsonObject

fun JsonArray.getArray(index: Int): JsonArray? =
    this.getOrNull(index) as? JsonArray

fun JsonArray.getBoolean(index: Int): Boolean? =
    (this.getOrNull(index) as? JsonPrimitive)?.booleanOrNull

fun JsonArray.getNumber(index: Int): Number? {
    val primitive = this.getOrNull(index) as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    primitive.longOrNull?.let { return it }
    primitive.doubleOrNull?.let { return it }
    return null
}

fun JsonArray.getElement(index: Int): JsonElement? = this.getOrNull(index)
