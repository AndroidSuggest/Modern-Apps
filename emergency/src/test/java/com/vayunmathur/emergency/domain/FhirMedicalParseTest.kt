package com.vayunmathur.emergency.domain

import com.vayunmathur.emergency.data.AllergyCriticality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parsing checks for the read-only FHIR display extraction the emergency card uses.
 *
 * Covers the display-name fallback chain (coding display → text → any coding), allergy
 * criticality and clinical-status filtering, and the current-medication status filter, plus
 * the malformed-input tolerance the importer relies on.
 */
class FhirMedicalParseTest {

    @Test
    fun allergyUsesCodingDisplayFirst() {
        val json = """
            {
              "resourceType": "AllergyIntolerance",
              "criticality": "high",
              "code": {
                "coding": [{
                  "system": "http://www.nlm.nih.gov/research/umls/rxnorm",
                  "code": "7980",
                  "display": "Penicillin"
                }],
                "text": "pen"
              },
              "reaction": [{ "manifestation": [{ "text": "Anaphylaxis" }] }]
            }
        """.trimIndent()
        val allergy = FhirMedicalParse.parseAllergy(json)
        assertEquals("Penicillin", allergy?.displayName)
        assertEquals(AllergyCriticality.High, allergy?.criticality)
        assertEquals("Anaphylaxis", allergy?.reaction)
    }

    @Test
    fun allergyFallsBackToText() {
        val json = """
            {
              "resourceType": "AllergyIntolerance",
              "code": { "text": "Shellfish" }
            }
        """.trimIndent()
        val allergy = FhirMedicalParse.parseAllergy(json)
        assertEquals("Shellfish", allergy?.displayName)
        // No criticality field -> Unknown; no reaction.
        assertEquals(AllergyCriticality.Unknown, allergy?.criticality)
        assertNull(allergy?.reaction)
    }

    @Test
    fun resolvedAllergyIsDropped() {
        val json = """
            {
              "resourceType": "AllergyIntolerance",
              "clinicalStatus": { "coding": [{ "code": "resolved" }] },
              "code": { "text": "Latex" }
            }
        """.trimIndent()
        assertNull(FhirMedicalParse.parseAllergy(json))
    }

    @Test
    fun allergyWithNoCodeIsDropped() {
        val json = """{ "resourceType": "AllergyIntolerance", "criticality": "low" }"""
        assertNull(FhirMedicalParse.parseAllergy(json))
    }

    @Test
    fun nonAllergyResourceIsDropped() {
        val json = """{ "resourceType": "Condition", "code": { "text": "Asthma" } }"""
        assertNull(FhirMedicalParse.parseAllergy(json))
    }

    @Test
    fun activeMedicationRequestIsParsed() {
        val json = """
            {
              "resourceType": "MedicationRequest",
              "status": "active",
              "medicationCodeableConcept": {
                "coding": [{
                  "system": "http://www.nlm.nih.gov/research/umls/rxnorm",
                  "code": "1049502",
                  "display": "Lisinopril 10mg"
                }]
              }
            }
        """.trimIndent()
        assertEquals("Lisinopril 10mg", FhirMedicalParse.parseCurrentMedication(json)?.displayName)
    }

    @Test
    fun completedMedicationIsDropped() {
        val json = """
            {
              "resourceType": "MedicationRequest",
              "status": "completed",
              "medicationCodeableConcept": { "text": "Amoxicillin" }
            }
        """.trimIndent()
        assertNull(FhirMedicalParse.parseCurrentMedication(json))
    }

    @Test
    fun medicationStatementCurrentIsParsed() {
        val json = """
            {
              "resourceType": "MedicationStatement",
              "status": "active",
              "medicationCodeableConcept": { "text": "Metformin" }
            }
        """.trimIndent()
        assertEquals("Metformin", FhirMedicalParse.parseCurrentMedication(json)?.displayName)
    }

    @Test
    fun malformedJsonIsTolerated() {
        assertNull(FhirMedicalParse.parseAllergy("not json at all"))
        assertNull(FhirMedicalParse.parseCurrentMedication("{ broken"))
    }

    @Test
    fun sortPutsHighCriticalityFirst() {
        val input = listOf(
            com.vayunmathur.emergency.data.ImportedAllergy("Pollen", AllergyCriticality.Low, null),
            com.vayunmathur.emergency.data.ImportedAllergy("Unknown one", AllergyCriticality.Unknown, null),
            com.vayunmathur.emergency.data.ImportedAllergy("Penicillin", AllergyCriticality.High, null),
        )
        val sorted = FhirMedicalParse.sortAllergies(input)
        assertEquals("Penicillin", sorted.first().displayName)
        assertEquals("Unknown one", sorted.last().displayName)
        assertTrue(sorted.size == 3)
    }
}
