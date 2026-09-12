package com.vayunmathur.health.platform

import androidx.lifecycle.viewModelScope
import com.vayunmathur.health.data.HealthProfile
import com.vayunmathur.health.data.PregnancyStatus
import com.vayunmathur.health.data.ProfileAnswer
import com.vayunmathur.health.data.SmokingStatus
import com.vayunmathur.health.domain.FhirRecords
import com.vayunmathur.health.domain.SocialHistoryQuestions
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Records a new pregnancy status, dated now.
 *
 * Each change writes a fresh observation rather than editing the last one, because "pregnant as
 * of March" and "not pregnant as of September" are both true statements about different moments
 * and a clinician reading the record needs the date attached.
 */
fun MedicalViewModel.setPregnancyStatus(status: PregnancyStatus, dueDate: LocalDate?) {
    viewModelScope.launch {
        val now = Instant.now()
        val current = repository.getProfile() ?: HealthProfile()
        val updated = current.copy(
            pregnancyStatus = status,
            dueDate = dueDate?.toInstant(),
            pregnancyRecordedAt = now,
            pregnancyFhirId = current.pregnancyFhirId ?: Uuid.random().toString(),
        )
        repository.upsertProfile(updated)
        mirrorProfile(pregnancy = true)
    }
}

fun MedicalViewModel.setSmokingStatus(status: SmokingStatus) {
    viewModelScope.launch {
        val now = Instant.now()
        val current = repository.getProfile() ?: HealthProfile()
        val updated = current.copy(
            smokingStatus = status,
            smokingRecordedAt = now,
            smokingFhirId = current.smokingFhirId ?: Uuid.random().toString(),
        )
        repository.upsertProfile(updated)
        mirrorProfile(pregnancy = false)
    }
}

internal suspend fun MedicalViewModel.mirrorProfile(pregnancy: Boolean) {
    val dataSourceId = PersonalHealthRecords.dataSourceId() ?: return
    val profile = repository.getProfile() ?: return
    if (pregnancy) {
        val id = profile.pregnancyFhirId ?: return
        val recordedAt = profile.pregnancyRecordedAt ?: return
        PersonalHealthRecords.upsert(
            dataSourceId,
            FhirRecords.pregnancyStatusJson(
                id, profile.pregnancyStatus, recordedAt, profile.dueDate
            ),
        )
    } else {
        val id = profile.smokingFhirId ?: return
        val recordedAt = profile.smokingRecordedAt ?: return
        PersonalHealthRecords.upsert(
            dataSourceId,
            FhirRecords.smokingStatusJson(id, profile.smokingStatus, recordedAt),
        )
    }
    repository.upsertProfile(profile.copy(dataSourceId = dataSourceId))
}

/** Records an answer to one social history question, dated now. */
fun MedicalViewModel.setSocialHistoryAnswer(
    question: SocialHistoryQuestions.Question,
    answer: SocialHistoryQuestions.Answer,
) {
    viewModelScope.launch {
        val existing = repository.getProfileAnswer(question.loinc)
        val row = ProfileAnswer(
            loincCode = question.loinc,
            answerCode = answer.code,
            recordedAt = Instant.now(),
            // Reusing the id is what makes this an update. Minting a new one leaves the old
            // observation behind, and the next import then sees two answers to one question.
            fhirResourceId = existing?.fhirResourceId ?: Uuid.random().toString(),
            dataSourceId = existing?.dataSourceId,
        )
        repository.upsertProfileAnswer(row)

        val dataSourceId = PersonalHealthRecords.dataSourceId() ?: return@launch
        val fhirId = row.fhirResourceId ?: return@launch
        PersonalHealthRecords.upsert(
            dataSourceId,
            FhirRecords.socialHistoryAnswerJson(fhirId, question, answer, row.recordedAt),
        ) ?: return@launch
        repository.upsertProfileAnswer(row.copy(dataSourceId = dataSourceId))
    }
}

/**
 * Un-answers a question, removing the observation as well as the local row.
 *
 * Deleting only locally would leave the answer in Health Connect for the next import to bring
 * straight back.
 */
fun MedicalViewModel.clearSocialHistoryAnswer(question: SocialHistoryQuestions.Question) {
    viewModelScope.launch {
        val existing = repository.getProfileAnswer(question.loinc)
        repository.deleteProfileAnswer(question.loinc)
        val dataSourceId = existing?.dataSourceId ?: return@launch
        val fhirId = existing.fhirResourceId ?: return@launch
        PersonalHealthRecords.delete(
            dataSourceId,
            PersonalHealthRecords.observationResourceType,
            fhirId,
        )
    }
}
