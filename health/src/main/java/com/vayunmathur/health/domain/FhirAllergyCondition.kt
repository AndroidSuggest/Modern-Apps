package com.vayunmathur.health.domain

import com.vayunmathur.health.data.AllergyCategory
import com.vayunmathur.health.data.AllergyCriticality
import com.vayunmathur.health.data.AllergyEntry
import com.vayunmathur.health.data.ConditionEntry
import com.vayunmathur.health.data.ConditionStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

internal fun allergyIntoleranceJson(entry: AllergyEntry): String = buildJsonObject {
    put("resourceType", "AllergyIntolerance")
    put("id", entry.id)
    // An allergy list is only safe to act on if "not currently allergic" is distinguishable
    // from "never recorded", so clinicalStatus is always written rather than left implicit.
    putJsonObject("clinicalStatus") {
        putJsonArray("coding") {
            add(
                buildJsonObject {
                    put(
                        "system",
                        "http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical",
                    )
                    put("code", "active")
                }
            )
        }
    }
    put("type", "allergy")
    putJsonArray("category") { add(JsonPrimitive(categoryToFhir(entry.category))) }
    criticalityToFhir(entry.criticality)?.let { put("criticality", it) }
    putJsonObject("code") {
        // Only medication allergens can be coded: RxNorm is the one terminology shipped that
        // covers them. Food and environmental allergens would need SNOMED CT, which cannot be
        // redistributed, so they travel as text and remain valid FHIR.
        if (entry.rxcui != null) {
            putJsonArray("coding") {
                add(
                    buildJsonObject {
                        put("system", FhirRecords.RXNORM_SYSTEM)
                        put("code", entry.rxcui)
                        put("display", entry.displayName)
                    }
                )
            }
        }
        put("text", entry.displayName)
    }
    putJsonObject("patient") { put("reference", "Patient/${FhirRecords.PATIENT_RESOURCE_ID}") }
    entry.onsetAt?.let { put("onsetDateTime", formatDateTime(it)) }
    put("recordedDate", formatDateTime(entry.recordedAt))
    entry.reaction?.takeIf { it.isNotBlank() }?.let { reaction ->
        putJsonArray("reaction") {
            add(
                buildJsonObject {
                    putJsonArray("manifestation") {
                        add(buildJsonObject { put("text", reaction) })
                    }
                }
            )
        }
    }
    entry.note?.takeIf { it.isNotBlank() }?.let {
        putJsonArray("note") { add(buildJsonObject { put("text", it) }) }
    }
}.toString()

internal fun parseAllergyIntolerance(data: String, dataSourceId: String): AllergyEntry? {
    val root = parseObject(data) ?: return null
    if (root.string("resourceType") != "AllergyIntolerance") return null
    val id = root.string("id") ?: return null

    val code = root["code"] as? JsonObject
    val coding = code.codingWithSystem(FhirRecords.RXNORM_SYSTEM)
    val displayName = coding?.string("display")
        ?: code?.string("text")
        ?: code.anyCoding()?.string("display")
        ?: return null

    return AllergyEntry(
        id = id,
        rxcui = coding?.string("code"),
        displayName = displayName,
        category = categoryFromFhir(
            (root["category"] as? JsonArray)
                ?.firstOrNull()
                ?.let { (it as? JsonPrimitive)?.content }
        ),
        criticality = criticalityFromFhir(root.string("criticality")),
        reaction = (root["reaction"] as? JsonArray)
            ?.firstNotNullOfOrNull { entry ->
                ((entry as? JsonObject)?.get("manifestation") as? JsonArray)
                    ?.firstNotNullOfOrNull { (it as? JsonObject).conceptText() }
            },
        onsetAt = parseDateTime(root.string("onsetDateTime")),
        recordedAt = parseDateTime(root.string("recordedDate")) ?: Instant.EPOCH,
        note = root.firstNoteText(),
        fhirResourceId = id,
        dataSourceId = dataSourceId,
    )
}

internal fun conditionJson(entry: ConditionEntry): String = buildJsonObject {
    put("resourceType", "Condition")
    put("id", entry.id)
    putJsonObject("clinicalStatus") {
        putJsonArray("coding") {
            add(
                buildJsonObject {
                    put("system", "http://terminology.hl7.org/CodeSystem/condition-clinical")
                    put("code", statusToFhir(entry.status))
                }
            )
        }
    }
    // US Core requires a category on Condition; "problem-list-item" is what a user-maintained
    // diagnosis list is, as opposed to an encounter diagnosis.
    putJsonArray("category") {
        add(
            buildJsonObject {
                putJsonArray("coding") {
                    add(
                        buildJsonObject {
                            put(
                                "system",
                                "http://terminology.hl7.org/CodeSystem/condition-category",
                            )
                            put("code", "problem-list-item")
                        }
                    )
                }
            }
        )
    }
    putJsonObject("code") {
        if (entry.icd10Code != null) {
            putJsonArray("coding") {
                add(
                    buildJsonObject {
                        put("system", FhirRecords.ICD10_SYSTEM)
                        put("code", entry.icd10Code)
                        put("display", entry.displayName)
                    }
                )
            }
        }
        put("text", entry.displayName)
    }
    putJsonObject("subject") { put("reference", "Patient/${FhirRecords.PATIENT_RESOURCE_ID}") }
    put("onsetDateTime", formatDateTime(entry.onsetAt))
    entry.resolvedAt?.let { put("abatementDateTime", formatDateTime(it)) }
    entry.note?.takeIf { it.isNotBlank() }?.let {
        putJsonArray("note") { add(buildJsonObject { put("text", it) }) }
    }
}.toString()

internal fun parseCondition(data: String, dataSourceId: String): ConditionEntry? {
    val root = parseObject(data) ?: return null
    if (root.string("resourceType") != "Condition") return null
    val id = root.string("id") ?: return null

    val code = root["code"] as? JsonObject
    val coding = code.codingWithSystem(FhirRecords.ICD10_SYSTEM)
    val displayName = coding?.string("display")
        ?: code?.string("text")
        ?: code.anyCoding()?.string("display")
        ?: return null

    // A provider may date a diagnosis by age or by a period rather than an instant, and may not
    // date it at all. Falling back to the recorded date keeps the row usable; without any date
    // it would sort unpredictably in a reverse-chronological list.
    val onset = parseDateTime(root.string("onsetDateTime"))
        ?: parseDateTime((root["onsetPeriod"] as? JsonObject).string("start"))
        ?: parseDateTime(root.string("recordedDate"))
        ?: Instant.EPOCH

    return ConditionEntry(
        id = id,
        icd10Code = coding?.string("code"),
        displayName = displayName,
        status = conditionStatusFromFhir(
            (root["clinicalStatus"] as? JsonObject).clinicalStatusCode()
        ),
        onsetAt = onset,
        resolvedAt = parseDateTime(root.string("abatementDateTime"))
            ?: parseDateTime((root["abatementPeriod"] as? JsonObject).string("end")),
        note = root.firstNoteText(),
        fhirResourceId = id,
        dataSourceId = dataSourceId,
    )
}

private fun categoryToFhir(category: AllergyCategory): String = when (category) {
    AllergyCategory.Medication -> "medication"
    AllergyCategory.Food -> "food"
    AllergyCategory.Environment -> "environment"
    AllergyCategory.Biologic -> "biologic"
}

private fun categoryFromFhir(value: String?): AllergyCategory = when (value) {
    "food" -> AllergyCategory.Food
    "environment" -> AllergyCategory.Environment
    "biologic" -> AllergyCategory.Biologic
    else -> AllergyCategory.Medication
}

private fun criticalityToFhir(criticality: AllergyCriticality): String? = when (criticality) {
    AllergyCriticality.Low -> "low"
    AllergyCriticality.High -> "high"
    AllergyCriticality.Unknown -> null
}

private fun criticalityFromFhir(value: String?): AllergyCriticality = when (value) {
    "low" -> AllergyCriticality.Low
    "high" -> AllergyCriticality.High
    else -> AllergyCriticality.Unknown
}

private fun statusToFhir(status: ConditionStatus): String = when (status) {
    ConditionStatus.Active -> "active"
    ConditionStatus.Recurrence -> "recurrence"
    ConditionStatus.Remission -> "remission"
    ConditionStatus.Resolved -> "resolved"
}

private fun conditionStatusFromFhir(value: String?): ConditionStatus = when (value) {
    "recurrence", "relapse" -> ConditionStatus.Recurrence
    "remission" -> ConditionStatus.Remission
    "resolved", "inactive" -> ConditionStatus.Resolved
    else -> ConditionStatus.Active
}
