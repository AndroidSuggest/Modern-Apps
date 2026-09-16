package com.vayunmathur.emergency.domain

import com.vayunmathur.emergency.data.AllergyCriticality
import com.vayunmathur.emergency.data.ImportedAllergy
import com.vayunmathur.emergency.data.ImportedMedication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads the display fields the emergency view needs out of FHIR R4 JSON.
 *
 * This is the read-only, emergency-scoped cousin of the health app's `FhirRecords`: that app
 * round-trips full `AllergyEntry`/`MedicationEntry` rows through Room, whereas here we only ever
 * show a name (and, for allergies, how bad the reaction is), so the parsing stops at the first
 * usable display string. Reading is forgiving on purpose - resources synced from a real health
 * system carry codings and extensions this never produces, and anything unrecognised is dropped
 * rather than throwing, exactly as `FhirRecords` does.
 *
 * Pure and Android-free so it can be unit-tested on the JVM; the Health Connect plumbing that
 * feeds it JSON lives in `platform/HealthConnectMedical`.
 */
object FhirMedicalParse {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** RxNorm, the one medication terminology Health Connect ships codings for. */
    private const val RXNORM_SYSTEM = "http://www.nlm.nih.gov/research/umls/rxnorm"

    /**
     * One `AllergyIntolerance` resource, or null when it is not one or has no usable name.
     *
     * Resolved (`clinicalStatus` inactive/resolved) allergies are dropped: an emergency card
     * should show what the patient is allergic to now, not what they have outgrown.
     */
    fun parseAllergy(data: String): ImportedAllergy? {
        val root = parse(data) ?: return null
        if (root.str("resourceType") != "AllergyIntolerance") return null
        if (!isActive(root["clinicalStatus"] as? JsonObject)) return null

        val code = root["code"] as? JsonObject
        val displayName = displayFrom(code) ?: return null

        val reaction = (root["reaction"] as? JsonArray)?.firstNotNullOfOrNull { entry ->
            ((entry as? JsonObject)?.get("manifestation") as? JsonArray)
                ?.firstNotNullOfOrNull { conceptText(it as? JsonObject) }
        }

        return ImportedAllergy(
            displayName = displayName,
            criticality = criticalityFrom(root.str("criticality")),
            reaction = reaction,
        )
    }

    /**
     * One current medication, or null when the resource is not a medication, has no usable
     * name, or is not currently being taken.
     *
     * Both `MedicationRequest` and `MedicationStatement` appear in the medications category;
     * either is accepted as long as its status marks it current (active / intended /
     * on-hold for a request; active / intended for a statement). Completed or stopped courses
     * are dropped - "current medication" is what the user asked for.
     */
    fun parseCurrentMedication(data: String): ImportedMedication? {
        val root = parse(data) ?: return null
        val type = root.str("resourceType")
        if (type != "MedicationRequest" && type != "MedicationStatement") return null
        if (!isCurrentMedicationStatus(root.str("status"))) return null

        val concept = root["medicationCodeableConcept"] as? JsonObject
        val displayName = displayFrom(concept) ?: return null
        return ImportedMedication(displayName = displayName)
    }

    /** High first, then low, then unknown - the order an emergency list should read in. */
    fun sortAllergies(allergies: List<ImportedAllergy>): List<ImportedAllergy> =
        allergies.sortedBy {
            when (it.criticality) {
                AllergyCriticality.High -> 0
                AllergyCriticality.Low -> 1
                AllergyCriticality.Unknown -> 2
            }
        }

    // --- helpers -------------------------------------------------------------

    private fun parse(data: String): JsonObject? =
        runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull()

    /** A coded concept's best display: coding display (RxNorm first) → text → any coding display. */
    private fun displayFrom(concept: JsonObject?): String? {
        if (concept == null) return null
        val codings = concept["coding"] as? JsonArray
        val rxnorm = codings?.firstOrNull {
            (it as? JsonObject).str("system") == RXNORM_SYSTEM
        } as? JsonObject
        return rxnorm.str("display")
            ?: concept.str("text")
            ?: (codings?.firstOrNull() as? JsonObject).str("display")
    }

    private fun conceptText(concept: JsonObject?): String? {
        if (concept == null) return null
        return concept.str("text")
            ?: ((concept["coding"] as? JsonArray)?.firstOrNull() as? JsonObject).str("display")
    }

    /** True unless clinicalStatus explicitly says inactive or resolved. Absent status = active. */
    private fun isActive(clinicalStatus: JsonObject?): Boolean {
        val code = (clinicalStatus?.get("coding") as? JsonArray)
            ?.firstNotNullOfOrNull { (it as? JsonObject).str("code") }
            ?: return true
        return code != "inactive" && code != "resolved"
    }

    private fun isCurrentMedicationStatus(status: String?): Boolean = when (status) {
        "active", "intended", "on-hold" -> true
        // A statement uses a narrower set; "active"/"intended" already covered above.
        else -> false
    }

    private fun criticalityFrom(value: String?): AllergyCriticality = when (value) {
        "high" -> AllergyCriticality.High
        "low" -> AllergyCriticality.Low
        else -> AllergyCriticality.Unknown
    }

    private fun JsonObject?.str(key: String): String? {
        val primitive = this?.get(key) as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }
}
