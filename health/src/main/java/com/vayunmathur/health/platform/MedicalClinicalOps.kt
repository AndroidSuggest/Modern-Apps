package com.vayunmathur.health.platform

import androidx.lifecycle.viewModelScope
import com.vayunmathur.health.data.AllergyEntry
import com.vayunmathur.health.data.ConditionEntry
import com.vayunmathur.health.data.LabResultEntry
import com.vayunmathur.health.domain.FhirRecords
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.uuid.Uuid

fun MedicalViewModel.saveAllergyDraft() {
    val draft = _allergyDraft.value
    if (draft.displayName.isBlank()) return
    viewModelScope.launch {
        val existing = draft.editingId?.let { repository.getAllergy(it) }
        val entry = (existing ?: AllergyEntry(
            id = Uuid.random().toString(),
            displayName = "",
            recordedAt = Instant.now(),
        )).copy(
            rxcui = draft.rxcui,
            displayName = draft.displayName.trim(),
            category = draft.category,
            criticality = draft.criticality,
            reaction = draft.reaction.blankToNull(),
            onsetAt = draft.onsetOn?.toInstant(),
            note = draft.note.blankToNull(),
        )
        repository.upsertAllergy(entry)
        mirrorAllergy(entry.id)
    }
}

fun MedicalViewModel.deleteAllergy(entry: AllergyEntry) {
    viewModelScope.launch {
        repository.deleteAllergy(entry)
        val dataSourceId = entry.dataSourceId
        val fhirId = entry.fhirResourceId
        if (dataSourceId != null && fhirId != null) {
            PersonalHealthRecords.delete(
                dataSourceId,
                PersonalHealthRecords.allergyResourceType,
                fhirId,
            )
        }
    }
}

private suspend fun MedicalViewModel.mirrorAllergy(id: String) {
    val dataSourceId = PersonalHealthRecords.dataSourceId() ?: return
    val entry = repository.getAllergy(id) ?: return
    val written = PersonalHealthRecords.upsert(
        dataSourceId,
        FhirRecords.allergyIntoleranceJson(entry),
    ) ?: return
    repository.upsertAllergy(entry.copy(fhirResourceId = written, dataSourceId = dataSourceId))
}

fun MedicalViewModel.saveConditionDraft() {
    val draft = _conditionDraft.value
    if (draft.displayName.isBlank()) return
    viewModelScope.launch {
        val existing = draft.editingId?.let { repository.getCondition(it) }
        val entry = (existing ?: ConditionEntry(
            id = Uuid.random().toString(),
            displayName = "",
            onsetAt = Instant.now(),
        )).copy(
            icd10Code = draft.icd10Code,
            displayName = draft.displayName.trim(),
            status = draft.status,
            onsetAt = draft.onsetOn.toInstant(),
            resolvedAt = draft.resolvedOn?.toInstant(),
            note = draft.note.blankToNull(),
        )
        repository.upsertCondition(entry)
        mirrorCondition(entry.id)
    }
}

fun MedicalViewModel.deleteCondition(entry: ConditionEntry) {
    viewModelScope.launch {
        repository.deleteCondition(entry)
        val dataSourceId = entry.dataSourceId
        val fhirId = entry.fhirResourceId
        if (dataSourceId != null && fhirId != null) {
            PersonalHealthRecords.delete(
                dataSourceId,
                PersonalHealthRecords.conditionResourceType,
                fhirId,
            )
        }
    }
}

private suspend fun MedicalViewModel.mirrorCondition(id: String) {
    val dataSourceId = PersonalHealthRecords.dataSourceId() ?: return
    val entry = repository.getCondition(id) ?: return
    val written = PersonalHealthRecords.upsert(
        dataSourceId,
        FhirRecords.conditionJson(entry),
    ) ?: return
    repository.upsertCondition(entry.copy(fhirResourceId = written, dataSourceId = dataSourceId))
}

fun MedicalViewModel.saveLabDraft() {
    val draft = _labDraft.value
    if (draft.displayName.isBlank()) return
    viewModelScope.launch {
        val existing = draft.editingId?.let { repository.getLabResult(it) }
        // A result is either a number or a word. Anything that does not parse is kept verbatim
        // as text rather than being coerced to zero, because "positive" and "trace" are real
        // results and silently turning one into 0.0 would be a clinically wrong record.
        val numeric = draft.value.trim().replace(',', '.').toDoubleOrNull()
        val entry = (existing ?: LabResultEntry(
            id = Uuid.random().toString(),
            displayName = "",
            takenAt = Instant.now(),
        )).copy(
            loincCode = draft.loincCode,
            displayName = draft.displayName.trim(),
            value = numeric,
            valueText = if (numeric == null) draft.value.blankToNull() else null,
            unit = draft.unit.blankToNull(),
            referenceLow = draft.referenceLow.trim().toDoubleOrNull(),
            referenceHigh = draft.referenceHigh.trim().toDoubleOrNull(),
            takenAt = draft.takenOn.toInstant(),
            note = draft.note.blankToNull(),
        )
        repository.upsertLabResult(entry)
        mirrorLabResult(entry.id)
    }
}

fun MedicalViewModel.deleteLabResult(entry: LabResultEntry) {
    viewModelScope.launch {
        repository.deleteLabResult(entry)
        val dataSourceId = entry.dataSourceId
        val fhirId = entry.fhirResourceId
        if (dataSourceId != null && fhirId != null) {
            PersonalHealthRecords.delete(
                dataSourceId,
                PersonalHealthRecords.observationResourceType,
                fhirId,
            )
        }
    }
}

private suspend fun MedicalViewModel.mirrorLabResult(id: String) {
    val dataSourceId = PersonalHealthRecords.dataSourceId() ?: return
    val entry = repository.getLabResult(id) ?: return
    val written = PersonalHealthRecords.upsert(
        dataSourceId,
        FhirRecords.labResultJson(entry),
    ) ?: return
    repository.upsertLabResult(entry.copy(fhirResourceId = written, dataSourceId = dataSourceId))
}
