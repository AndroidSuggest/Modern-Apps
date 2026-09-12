package com.vayunmathur.health.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.format.DateTimeFormatter

internal val fhirJson = Json { ignoreUnknownKeys = true }

internal fun parseObject(data: String): JsonObject? = try {
    fhirJson.parseToJsonElement(data) as? JsonObject
} catch (_: Exception) {
    null
}

internal fun JsonObject?.string(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

internal fun JsonObject?.codingWithSystem(system: String): JsonObject? =
    (this?.get("coding") as? JsonArray)
        ?.firstOrNull { (it as? JsonObject).string("system") == system } as? JsonObject

internal fun JsonObject?.anyCoding(): JsonObject? =
    (this?.get("coding") as? JsonArray)?.firstOrNull() as? JsonObject

internal fun JsonObject?.conceptText(): String? =
    this.string("text") ?: this.anyCoding()?.string("display")

internal fun JsonObject.firstNoteText(): String? =
    (this["note"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonObject)?.string("text") }

/** The code carried in a `clinicalStatus`-shaped CodeableConcept. */
internal fun JsonObject?.clinicalStatusCode(): String? =
    this.anyCoding()?.string("code") ?: this.string("text")

private const val OBSERVATION_CATEGORY_SYSTEM =
    "http://terminology.hl7.org/CodeSystem/observation-category"

internal fun JsonObjectBuilder.putObservationCategory(code: String) {
    putJsonArray("category") {
        add(
            buildJsonObject {
                putJsonArray("coding") {
                    add(
                        buildJsonObject {
                            put("system", OBSERVATION_CATEGORY_SYSTEM)
                            put("code", code)
                        }
                    )
                }
            }
        )
    }
}

/** A CodeableConcept with exactly one coding, which is all any of these need. */
internal fun JsonObjectBuilder.putCoded(
    key: String,
    system: String,
    code: String,
    display: String,
) {
    putJsonObject(key) {
        putJsonArray("coding") {
            add(
                buildJsonObject {
                    put("system", system)
                    put("code", code)
                    put("display", display)
                }
            )
        }
        put("text", display)
    }
}

internal fun JsonObjectBuilder.putQuantity(value: Double, unit: String?) {
    put("value", value)
    unit?.takeIf { it.isNotBlank() }?.let {
        put("unit", it)
        put("system", FhirRecords.UCUM_SYSTEM)
        put("code", it)
    }
}

internal fun formatDateTime(instant: Instant): String =
    DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))

internal fun formatQuantity(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
