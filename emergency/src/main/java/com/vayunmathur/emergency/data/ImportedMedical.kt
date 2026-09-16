package com.vayunmathur.emergency.data

/**
 * A single allergy read from Health Connect's Personal Health Record store.
 *
 * Read-only: unlike the free-text medical fields, these come from a provider or pharmacy sync
 * and are never edited here, so there is no id, onset date or FHIR round-trip - just what a
 * first responder needs to read off the screen.
 */
data class ImportedAllergy(
    val displayName: String,
    val criticality: AllergyCriticality,
    val reaction: String?,
)

/** A single current medication read from Health Connect. Read-only, like [ImportedAllergy]. */
data class ImportedMedication(
    val displayName: String,
)

/** A single active condition read from Health Connect. Read-only, like [ImportedAllergy]. */
data class ImportedCondition(
    val displayName: String,
)

/** How dangerous a reaction is, mirroring FHIR `AllergyIntolerance.criticality`. */
enum class AllergyCriticality { High, Low, Unknown }
