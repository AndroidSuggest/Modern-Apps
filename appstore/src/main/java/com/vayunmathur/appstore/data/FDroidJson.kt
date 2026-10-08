package com.vayunmathur.appstore.data

import android.util.JsonReader
import android.util.JsonToken
import java.io.IOException

/**
 * Scalar JsonReader helpers for the F-Droid index-v2 streaming parser.
 *
 * Extracted from [FDroidRepository] so the repository object stays under
 * detekt's TooManyFunctions cap. All functions are internal top-level:
 * same module, no behavior change.
 */

internal fun readLocalizedString(reader: JsonReader): String? {
    return when (reader.peek()) {
        JsonToken.STRING -> reader.nextString()
        JsonToken.BEGIN_OBJECT -> readLocalizedObject(reader)
        else -> {
            reader.skipValue()
            null
        }
    }
}

private fun readLocalizedObject(reader: JsonReader): String? {
    var first: String? = null
    var enUs: String? = null
    var en: String? = null
    reader.beginObject()
    while (reader.hasNext()) {
        val locale = reader.nextName()
        if (reader.peek() != JsonToken.STRING) {
            reader.skipValue()
            continue
        }
        val v = reader.nextString()
        if (first == null) first = v
        if (locale == LOCALE_US) enUs = v
        if (locale == LOCALE_EN) en = v
    }
    reader.endObject()
    return enUs ?: en ?: first
}

internal fun readIconName(reader: JsonReader): String? {
    // icon can be: { "en-US": { "96": {"name":...} } } or { "en-US": {"name":...} }
    return try {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var found: String? = null
        reader.beginObject()
        while (reader.hasNext() && found == null) {
            reader.nextName() // locale
            found = findFirstNameInAnyNested(reader)
        }
        while (reader.hasNext()) {
            reader.nextName()
            reader.skipValue()
        }
        reader.endObject()
        found
    } catch (_: IOException) {
        null
    } catch (_: IllegalStateException) {
        null
    }
}

internal fun findFirstNameInAnyNested(reader: JsonReader): String? {
    // searches recursively for first object containing key "name" = String
    return when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> findNameInObject(reader)
        JsonToken.BEGIN_ARRAY -> findNameInArray(reader)
        else -> {
            reader.skipValue()
            null
        }
    }
}

private fun findNameInObject(reader: JsonReader): String? {
    var found: String? = null
    reader.beginObject()
    while (reader.hasNext()) {
        val key = reader.nextName()
        found = if (found == null) {
            readNameOrNested(reader, key)
        } else {
            // The name is already in hand, but the value still has to be consumed —
            // leaving it on the stream desyncs the reader and everything after this
            // object misparses (every icon-bearing package dropped from the index).
            reader.skipValue()
            found
        }
    }
    reader.endObject()
    return found
}

private fun readNameOrNested(reader: JsonReader, key: String): String? {
    if (key == "name" && reader.peek() == JsonToken.STRING) {
        return reader.nextString()
    }
    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
        return findFirstNameInAnyNested(reader)
    }
    reader.skipValue()
    return null
}

private fun findNameInArray(reader: JsonReader): String? {
    reader.beginArray()
    var found: String? = null
    while (reader.hasNext() && found == null) {
        found = findFirstNameInAnyNested(reader)
    }
    while (reader.hasNext()) reader.skipValue()
    reader.endArray()
    return found
}

/**
 * index-v2 `screenshots`: `{ phone: { "en-US": [ { name, sha256, size }, … ] }, … }`.
 *
 * Only the phone set is taken — the tablet, TV and wear sets are the same app shot on
 * hardware the reader isn't holding, and mixing them makes the carousel jump between
 * aspect ratios. Falls back to whichever set exists if there is no phone one.
 */
internal fun readScreenshotsV2(reader: JsonReader, repoBase: String): List<String> {
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
        if (kind == SCREENSHOT_PHONE) {
            phone = shots
        } else if (fallback.isEmpty()) {
            fallback = shots
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
            LOCALE_US -> enUs = names
            LOCALE_EN -> en = names
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
internal fun readObjectKeys(reader: JsonReader): List<String> {
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

internal fun readStringArray(reader: JsonReader): List<String> {
    return try {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return emptyList()
        }
        val list = mutableListOf<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.STRING) {
                list.add(reader.nextString())
            } else {
                reader.skipValue()
            }
        }
        reader.endArray()
        list
    } catch (_: IOException) {
        emptyList()
    } catch (_: IllegalStateException) {
        emptyList()
    }
}

internal fun nextStringOrNull(reader: JsonReader): String? {
    return try {
        when (reader.peek()) {
            JsonToken.STRING -> reader.nextString()
            JsonToken.NULL -> {
                reader.nextNull()
                null
            }
            JsonToken.NUMBER -> reader.nextString()
            else -> {
                reader.skipValue()
                null
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalStateException) {
        null
    }
}

internal fun nextLongOrNull(reader: JsonReader): Long? {
    return try {
        when (reader.peek()) {
            JsonToken.NUMBER -> reader.nextLong()
            JsonToken.STRING -> reader.nextString().toLongOrNull()
            JsonToken.NULL -> {
                reader.nextNull()
                null
            }
            else -> {
                reader.skipValue()
                null
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalStateException) {
        null
    }
}

internal fun nextIntOrNull(reader: JsonReader): Int? {
    return try {
        when (reader.peek()) {
            JsonToken.NUMBER -> reader.nextInt()
            JsonToken.STRING -> reader.nextString().toIntOrNull()
            JsonToken.NULL -> {
                reader.nextNull()
                null
            }
            else -> {
                reader.skipValue()
                null
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalStateException) {
        null
    }
}

private const val LOCALE_US = "en-US"
private const val LOCALE_EN = "en"
private const val SCREENSHOT_PHONE = "phone"
