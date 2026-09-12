package com.vayunmathur.health.domain

import com.vayunmathur.health.data.VaccinationEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

internal fun patientJson(): String = buildJsonObject {
    put("resourceType", "Patient")
    put("id", FhirRecords.PATIENT_RESOURCE_ID)
}.toString()

internal fun immunizationJson(
    entry: VaccinationEntry,
    attachments: List<FhirRecords.FhirAttachment> = emptyList(),
): String = buildJsonObject {
    put("resourceType", "Immunization")
    put("id", entry.id)
    put("status", "completed")
    putJsonObject("vaccineCode") {
        if (entry.cvxCode != null) {
            putJsonArray("coding") {
                add(
                    buildJsonObject {
                        put("system", FhirRecords.CVX_SYSTEM)
                        put("code", entry.cvxCode)
                        put("display", entry.displayName)
                    }
                )
            }
        }
        put("text", entry.displayName)
    }
    putJsonObject("patient") { put("reference", "Patient/${FhirRecords.PATIENT_RESOURCE_ID}") }
    put("occurrenceDateTime", formatDateTime(entry.occurredAt))
    entry.lotNumber?.takeIf { it.isNotBlank() }?.let { put("lotNumber", it) }
    entry.site?.takeIf { it.isNotBlank() }?.let { putJsonObject("site") { put("text", it) } }
    entry.route?.takeIf { it.isNotBlank() }?.let { putJsonObject("route") { put("text", it) } }

    // doseQuantity is a SimpleQuantity with no text field, so a dose the user typed freehand can
    // only be carried when it splits cleanly into a number and a unit. Anything else would have
    // to be dropped, so it falls through to the note instead.
    val dose = entry.doseQuantity?.let(::parseQuantity)
    if (dose != null) {
        putJsonObject("doseQuantity") {
            put("value", dose.first)
            put("unit", dose.second)
        }
    }
    entry.performer?.takeIf { it.isNotBlank() }?.let {
        putJsonArray("performer") {
            add(buildJsonObject { putJsonObject("actor") { put("display", it) } })
        }
    }

    val unparsedDose = entry.doseQuantity?.takeIf { it.isNotBlank() && dose == null }
    val notes = listOfNotNull(entry.note?.takeIf { it.isNotBlank() }, unparsedDose)
    if (notes.isNotEmpty()) {
        putJsonArray("note") {
            notes.forEach { add(buildJsonObject { put("text", it) }) }
        }
    }

    if (attachments.isNotEmpty()) {
        putJsonArray("extension") {
            attachments.forEach { attachment ->
                add(
                    buildJsonObject {
                        put("url", FhirRecords.ATTACHMENT_EXTENSION_URL)
                        putJsonObject("valueAttachment") {
                            put("contentType", attachment.contentType)
                            put("title", attachment.title)
                            put("url", attachment.url)
                            put("creation", formatDateTime(attachment.creation))
                        }
                    }
                )
            }
        }
    }
}.toString()

internal fun parseImmunization(data: String, dataSourceId: String): VaccinationEntry? {
    val root = parseObject(data) ?: return null
    if (root.string("resourceType") != "Immunization") return null
    val id = root.string("id") ?: return null

    val vaccineCode = root["vaccineCode"] as? JsonObject
    val coding = vaccineCode.codingWithSystem(FhirRecords.CVX_SYSTEM)
    val displayName = coding?.string("display")
        ?: vaccineCode?.string("text")
        ?: vaccineCode.anyCoding()?.string("display")
        ?: return null

    val occurredAt = parseDateTime(root.string("occurrenceDateTime")) ?: return null

    return VaccinationEntry(
        id = id,
        cvxCode = coding?.string("code"),
        displayName = displayName,
        occurredAt = occurredAt,
        lotNumber = root.string("lotNumber"),
        site = (root["site"] as? JsonObject).conceptText(),
        route = (root["route"] as? JsonObject).conceptText(),
        doseQuantity = (root["doseQuantity"] as? JsonObject)?.let { quantity ->
            val value = quantity["value"]?.jsonPrimitive?.doubleOrNull ?: return@let null
            listOfNotNull(formatQuantity(value), quantity.string("unit")).joinToString(" ")
        },
        performer = (root["performer"] as? JsonArray)
            ?.firstNotNullOfOrNull { (it as? JsonObject)?.get("actor") as? JsonObject }
            ?.string("display"),
        note = root.firstNoteText(),
        fhirResourceId = id,
        dataSourceId = dataSourceId,
    )
}

/** Splits "0.5 mL" into 0.5 and "mL". Null when the text does not start with a number + unit. */
internal fun parseQuantity(text: String): Pair<Double, String>? {
    val match = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*(.*)$""").find(text) ?: return null
    val value = match.groupValues[1].toDoubleOrNull() ?: return null
    val unit = match.groupValues[2].trim()
    return if (unit.isEmpty()) null else value to unit
}

internal fun parseDateTime(text: String?): Instant? {
    if (text.isNullOrBlank()) return null
    runCatching { return Instant.parse(text) }
    runCatching { return java.time.OffsetDateTime.parse(text).toInstant() }
    runCatching {
        return java.time.LocalDate.parse(text)
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
    }
    runCatching {
        return java.time.YearMonth.parse(text).atDay(1)
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
    }
    runCatching {
        return java.time.Year.parse(text).atDay(1)
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
    }
    return null
}
