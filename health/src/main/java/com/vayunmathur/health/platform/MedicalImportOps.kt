package com.vayunmathur.health.platform

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.vayunmathur.health.data.HealthProfile
import com.vayunmathur.health.data.ProfileAnswer
import com.vayunmathur.health.domain.FhirRecords
import com.vayunmathur.health.domain.SocialHistoryQuestions
import kotlinx.coroutines.launch

/**
 * Pulls vaccinations and medications out of Health Connect into Room.
 *
 * This is what surfaces records a hospital or pharmacy has synced in, so it deliberately reads
 * every data source rather than only this app's. Rows the app wrote are matched on their FHIR id
 * and left alone; anything else is inserted.
 */
fun MedicalViewModel.importFromHealthConnect() {
    if (_syncing.value || !PersonalHealthRecords.isAvailable()) return
    viewModelScope.launch {
        _syncing.value = true
        try {
            val vaccines = PersonalHealthRecords.readAll(PersonalHealthRecords.vaccinesType)
                .mapNotNull { resource ->
                    FhirRecords.parseImmunization(resource.data, resource.dataSourceId)
                }
            if (vaccines.isNotEmpty()) repository.upsertVaccinations(vaccines)

            // Medications are MedicationRequests; a MedicationStatement in the same category is
            // a dose that was taken. Old records written by an earlier build are plain
            // statements with no basedOn, so they parse as neither and are skipped rather than
            // being misread as doses.
            val medResources = PersonalHealthRecords.readAll(PersonalHealthRecords.medicationsType)

            val meds = medResources.mapNotNull { resource ->
                FhirRecords.parseMedicationRequest(resource.data, resource.dataSourceId)
            }
            if (meds.isNotEmpty()) {
                repository.upsertMedications(meds.map { it.first })
                // An imported schedule arrives disabled, so this cannot start alarms on its own.
                meds.mapNotNull { it.second }.forEach { repository.upsertSchedule(it) }
            }

            val doses = medResources.mapNotNull { resource ->
                FhirRecords.parseDoseEvent(resource.data, resource.dataSourceId)
            }
            if (doses.isNotEmpty()) repository.upsertDoseEvents(doses)

            val allergyRows = PersonalHealthRecords.readAll(PersonalHealthRecords.allergiesType)
                .mapNotNull { resource ->
                    FhirRecords.parseAllergyIntolerance(resource.data, resource.dataSourceId)
                }
            if (allergyRows.isNotEmpty()) repository.upsertAllergies(allergyRows)

            val conditionRows = PersonalHealthRecords.readAll(PersonalHealthRecords.conditionsType)
                .mapNotNull { resource ->
                    FhirRecords.parseCondition(resource.data, resource.dataSourceId)
                }
            if (conditionRows.isNotEmpty()) repository.upsertConditions(conditionRows)

            val labRows = PersonalHealthRecords.readAll(PersonalHealthRecords.labsType)
                .mapNotNull { resource ->
                    FhirRecords.parseLabResult(resource.data, resource.dataSourceId)
                }
            if (labRows.isNotEmpty()) repository.upsertLabResults(labRows)

            importProfile()
        } catch (e: Exception) {
            Log.e("MedicalViewModel", "Import from Health Connect failed", e)
        } finally {
            _syncing.value = false
        }
    }
}

/**
 * Pulls the two standing observations back out of Health Connect.
 *
 * Both live in the same PHR category and are told apart by their LOINC code, since a category
 * read returns every social-history observation a provider has ever written — alcohol use,
 * housing status and the rest — not just the two this app writes. Only the newest of each is
 * kept: these are current answers, not a log.
 */
private suspend fun MedicalViewModel.importProfile() {
    val resources = PersonalHealthRecords.readAll(PersonalHealthRecords.socialHistoryType) +
        PersonalHealthRecords.readAll(PersonalHealthRecords.pregnancyType)
    if (resources.isEmpty()) return

    var profile = repository.getProfile() ?: HealthProfile()

    // Newest answer per question. A category read returns every social-history observation the
    // phone holds, which can include several for the same question - from a provider, or from
    // an older build of this app - and picking whichever came last in the list is how an answer
    // appears to revert after being set.
    val newest = mutableMapOf<String, ProfileAnswer>()

    resources.forEach { resource ->
        val loinc = FhirRecords.observationLoincCode(resource.data)
        when (loinc) {
            FhirRecords.LOINC_PREGNANCY_STATUS ->
                FhirRecords.parsePregnancyStatus(resource.data)?.let { (status, due) ->
                    profile = profile.copy(
                        pregnancyStatus = status,
                        dueDate = due,
                        pregnancyFhirId = profile.pregnancyFhirId
                            ?: FhirRecords.resourceId(resource.data),
                    )
                }
            FhirRecords.LOINC_SMOKING_STATUS ->
                FhirRecords.parseSmokingStatus(resource.data)?.let { status ->
                    profile = profile.copy(
                        smokingStatus = status,
                        smokingFhirId = profile.smokingFhirId
                            ?: FhirRecords.resourceId(resource.data),
                    )
                }
            else -> {
                // Anything else in this category that is one of ours, matched by question code.
                // A provider's social history contains plenty this app does not ask about.
                val question = loinc?.let { SocialHistoryQuestions.byLoinc(it) } ?: return@forEach
                val answerCode = FhirRecords.parseSocialHistoryAnswer(resource.data)
                    ?: return@forEach
                // Refuse an answer outside the question's own list rather than storing a code
                // the selector could never show.
                if (question.answers.none { it.code == answerCode }) return@forEach

                val candidate = ProfileAnswer(
                    loincCode = question.loinc,
                    answerCode = answerCode,
                    // The observation's own date, not now. Using now would make every import
                    // look like the freshest answer and would misdate the "Recorded" line.
                    recordedAt = FhirRecords.observationEffective(resource.data)
                        ?: return@forEach,
                    fhirResourceId = FhirRecords.resourceId(resource.data),
                    dataSourceId = resource.dataSourceId,
                )
                val held = newest[question.loinc]
                if (held == null || candidate.recordedAt.isAfter(held.recordedAt)) {
                    newest[question.loinc] = candidate
                }
            }
        }
    }

    newest.values.forEach { incoming ->
        val local = repository.getProfileAnswer(incoming.loincCode)
        // Never overwrite a newer local answer. The user may have just tapped one while this
        // import was still reading, and their answer should win over what was on disk before.
        if (local == null || incoming.recordedAt.isAfter(local.recordedAt)) {
            repository.upsertProfileAnswer(incoming)
        }
    }
    repository.upsertProfile(profile)
}
