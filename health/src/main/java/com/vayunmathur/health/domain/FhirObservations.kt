package com.vayunmathur.health.domain

import com.vayunmathur.health.data.LabResultEntry
import com.vayunmathur.health.data.PregnancyStatus
import com.vayunmathur.health.data.SmokingStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

/**
 * A lab result, as an `Observation` in the `laboratory` category.
 *
 * `valueQuantity` when the result is a number, `valueString` when it is not — "positive",
 * "trace", "not detected" are all real results and none of them is a quantity. FHIR draws the
 * same distinction, so nothing has to be invented to carry them.
 */
internal fun labResultJson(entry: LabResultEntry): String = buildJsonObject {
    put("resourceType", "Observation")
    put("id", entry.id)
    put("status", "final")
    putObservationCategory("laboratory")
    putJsonObject("code") {
        if (entry.loincCode != null) {
            putJsonArray("coding") {
                add(
                    buildJsonObject {
                        put("system", FhirRecords.LOINC_SYSTEM)
                        put("code", entry.loincCode)
                        put("display", entry.displayName)
                    }
                )
            }
        }
        put("text", entry.displayName)
    }
    putJsonObject("subject") { put("reference", "Patient/${FhirRecords.PATIENT_RESOURCE_ID}") }
    put("effectiveDateTime", formatDateTime(entry.takenAt))

    val numeric = entry.value
    if (numeric != null) {
        putJsonObject("valueQuantity") {
            put("value", numeric)
            entry.unit?.takeIf { it.isNotBlank() }?.let {
                put("unit", it)
                put("system", FhirRecords.UCUM_SYSTEM)
                put("code", it)
            }
        }
    } else {
        entry.valueText?.takeIf { it.isNotBlank() }?.let { put("valueString", it) }
    }

    if (entry.referenceLow != null || entry.referenceHigh != null) {
        putJsonArray("referenceRange") {
            add(
                buildJsonObject {
                    entry.referenceLow?.let {
                        putJsonObject("low") { putQuantity(it, entry.unit) }
                    }
                    entry.referenceHigh?.let {
                        putJsonObject("high") { putQuantity(it, entry.unit) }
                    }
                }
            )
        }
    }
    entry.note?.takeIf { it.isNotBlank() }?.let {
        putJsonArray("note") { add(buildJsonObject { put("text", it) }) }
    }
}.toString()

/**
 * Pregnancy status, as the `Observation` US Core defines.
 *
 * A coded answer rather than a boolean, because "unknown" is a distinct and clinically
 * meaningful third state — which is why [PregnancyStatus.Unknown] is never written at all rather
 * than being recorded as "not pregnant".
 */
internal fun pregnancyStatusJson(
    id: String,
    status: PregnancyStatus,
    recordedAt: Instant,
    dueDate: Instant?,
): String = buildJsonObject {
    put("resourceType", "Observation")
    put("id", id)
    put("status", "final")
    putObservationCategory("social-history")
    putCoded("code", FhirRecords.LOINC_SYSTEM, FhirRecords.LOINC_PREGNANCY_STATUS, "Pregnancy status")
    putJsonObject("subject") { put("reference", "Patient/${FhirRecords.PATIENT_RESOURCE_ID}") }
    put("effectiveDateTime", formatDateTime(recordedAt))
    val (code, display) = pregnancyCoding(status)
    putCoded("valueCodeableConcept", FhirRecords.SNOMED_SYSTEM, code, display)
    // The estimated delivery date rides along as its own component rather than a second
    // resource, which is how US Core carries it.
    dueDate?.let {
        putJsonArray("component") {
            add(
                buildJsonObject {
                    putCoded("code", FhirRecords.LOINC_SYSTEM, "11778-8", "Estimated date of delivery")
                    put("valueDateTime", formatDateTime(it))
                }
            )
        }
    }
}.toString()

/** Tobacco smoking status, as the `Observation` US Core requires. */
internal fun smokingStatusJson(id: String, status: SmokingStatus, recordedAt: Instant): String =
    buildJsonObject {
        put("resourceType", "Observation")
        put("id", id)
        put("status", "final")
        putObservationCategory("social-history")
        putCoded("code", FhirRecords.LOINC_SYSTEM, FhirRecords.LOINC_SMOKING_STATUS, "Tobacco smoking status")
        putJsonObject("subject") { put("reference", "Patient/${FhirRecords.PATIENT_RESOURCE_ID}") }
        put("effectiveDateTime", formatDateTime(recordedAt))
        val (code, display) = smokingCoding(status)
        putCoded("valueCodeableConcept", FhirRecords.SNOMED_SYSTEM, code, display)
    }.toString()

/**
 * One answered social history question, as an `Observation` carrying a LOINC answer code.
 *
 * Unlike smoking and pregnancy, whose US Core profiles bind to SNOMED, these questions have
 * LOINC's own normative answer lists — so both the question and the answer are LOINC codes and
 * no second terminology is involved.
 */
internal fun socialHistoryAnswerJson(
    id: String,
    question: SocialHistoryQuestions.Question,
    answer: SocialHistoryQuestions.Answer,
    recordedAt: Instant,
): String = buildJsonObject {
    put("resourceType", "Observation")
    put("id", id)
    put("status", "final")
    putObservationCategory("social-history")
    putCoded("code", FhirRecords.LOINC_SYSTEM, question.loinc, question.display)
    putJsonObject("subject") { put("reference", "Patient/${FhirRecords.PATIENT_RESOURCE_ID}") }
    put("effectiveDateTime", formatDateTime(recordedAt))
    putCoded("valueCodeableConcept", FhirRecords.LOINC_SYSTEM, answer.code, answer.display)
}.toString()

/** The LA answer code from a social history observation, if it carries one. */
internal fun parseSocialHistoryAnswer(data: String): String? {
    val root = parseObject(data) ?: return null
    if (root.string("resourceType") != "Observation") return null
    return (root["valueCodeableConcept"] as? JsonObject)
        .codingWithSystem(FhirRecords.LOINC_SYSTEM)?.string("code")
}

internal fun parseLabResult(data: String, dataSourceId: String): LabResultEntry? {
    val root = parseObject(data) ?: return null
    if (root.string("resourceType") != "Observation") return null
    val id = root.string("id") ?: return null

    val code = root["code"] as? JsonObject
    val coding = code.codingWithSystem(FhirRecords.LOINC_SYSTEM)
    val displayName = coding?.string("display")
        ?: code?.string("text")
        ?: code.anyCoding()?.string("display")
        ?: return null

    val quantity = root["valueQuantity"] as? JsonObject
    val range = (root["referenceRange"] as? JsonArray)?.firstOrNull() as? JsonObject

    return LabResultEntry(
        id = id,
        loincCode = coding?.string("code"),
        displayName = displayName,
        value = quantity?.get("value")?.jsonPrimitive?.doubleOrNull,
        valueText = root.string("valueString")
            ?: (root["valueCodeableConcept"] as? JsonObject).conceptText(),
        unit = quantity.string("unit") ?: quantity.string("code"),
        referenceLow = (range?.get("low") as? JsonObject)
            ?.get("value")?.jsonPrimitive?.doubleOrNull,
        referenceHigh = (range?.get("high") as? JsonObject)
            ?.get("value")?.jsonPrimitive?.doubleOrNull,
        takenAt = parseDateTime(root.string("effectiveDateTime"))
            ?: parseDateTime(root.string("issued"))
            ?: return null,
        note = root.firstNoteText(),
        fhirResourceId = id,
        dataSourceId = dataSourceId,
    )
}

/** The LOINC code an observation is about, so a reader can tell the three kinds apart. */
internal fun observationLoincCode(data: String): String? {
    val root = parseObject(data) ?: return null
    if (root.string("resourceType") != "Observation") return null
    return (root["code"] as? JsonObject).codingWithSystem(FhirRecords.LOINC_SYSTEM)?.string("code")
}

/** The resource's own `id`, needed to update rather than duplicate it on the next write. */
internal fun resourceId(data: String): String? = parseObject(data)?.string("id")

/** When an observation says it was taken, which is what orders two answers to one question. */
internal fun observationEffective(data: String): Instant? {
    val root = parseObject(data) ?: return null
    return parseDateTime(root.string("effectiveDateTime"))
        ?: parseDateTime(root.string("issued"))
        ?: parseDateTime((root["effectivePeriod"] as? JsonObject).string("start"))
}

/** The status and estimated delivery date from a pregnancy observation. */
internal fun parsePregnancyStatus(data: String): Pair<PregnancyStatus, Instant?>? {
    val root = parseObject(data) ?: return null
    val value = (root["valueCodeableConcept"] as? JsonObject)
        .codingWithSystem(FhirRecords.SNOMED_SYSTEM)?.string("code")
    val due = (root["component"] as? JsonArray)
        ?.firstNotNullOfOrNull { (it as? JsonObject)?.string("valueDateTime") }
        ?.let(::parseDateTime)
    return pregnancyFromCode(value) to due
}

internal fun parseSmokingStatus(data: String): SmokingStatus? {
    val root = parseObject(data) ?: return null
    val value = (root["valueCodeableConcept"] as? JsonObject)
        .codingWithSystem(FhirRecords.SNOMED_SYSTEM)?.string("code")
    return smokingFromCode(value)
}

// SNOMED CT concept ids from the US Core value sets. Referencing a handful of specific codes
// is not the same as shipping SNOMED, which would need a licence.
private fun pregnancyCoding(status: PregnancyStatus): Pair<String, String> = when (status) {
    PregnancyStatus.Pregnant -> "77386006" to "Pregnant"
    PregnancyStatus.NotPregnant -> "60001007" to "Not pregnant"
    PregnancyStatus.Unknown -> "261665006" to "Unknown"
}

private fun pregnancyFromCode(code: String?): PregnancyStatus = when (code) {
    "77386006" -> PregnancyStatus.Pregnant
    "60001007" -> PregnancyStatus.NotPregnant
    else -> PregnancyStatus.Unknown
}

private fun smokingCoding(status: SmokingStatus): Pair<String, String> = when (status) {
    SmokingStatus.Current -> "449868002" to "Current every day smoker"
    SmokingStatus.Former -> "8517006" to "Former smoker"
    SmokingStatus.Never -> "266919005" to "Never smoker"
    SmokingStatus.Unknown -> "266927001" to "Unknown if ever smoked"
}

private fun smokingFromCode(code: String?): SmokingStatus = when (code) {
    "449868002", "428041000124106", "77176002" -> SmokingStatus.Current
    "8517006" -> SmokingStatus.Former
    "266919005" -> SmokingStatus.Never
    else -> SmokingStatus.Unknown
}
