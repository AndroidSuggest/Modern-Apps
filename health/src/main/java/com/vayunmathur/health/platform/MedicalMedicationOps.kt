package com.vayunmathur.health.platform

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.vayunmathur.health.data.DoseEvent
import com.vayunmathur.health.data.MedicationEntry
import com.vayunmathur.health.data.MedicationSchedule
import com.vayunmathur.health.domain.FhirRecords
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.uuid.Uuid

fun MedicalViewModel.saveMedicationDraft() {
    val draft = _medicationDraft.value
    if (draft.ingredient.isBlank()) return
    viewModelScope.launch {
        val existing = draft.editingId?.let { repository.getMedication(it) }
        val entry = (existing ?: MedicationEntry(
            id = Uuid.random().toString(),
            displayName = "",
            startedAt = Instant.now(),
        )).copy(
            rxcui = draft.rxcui,
            displayName = draft.ingredient.trim(),
            strength = draft.strength,
            doseForm = draft.doseForm,
            status = draft.status,
            startedAt = draft.startedOn.toInstant(),
            endedAt = draft.endedOn?.toInstant(),
            dosageText = draft.dosage.blankToNull(),
            note = draft.note.blankToNull(),
        )
        repository.upsertMedication(entry)
        saveSchedule(draft, entry.id)
        mirrorMedication(entry.id)
    }
}

/**
 * Writes or clears the reminder schedule for [medicationId] and re-arms the alarm.
 *
 * Always re-arms from the stored row rather than the draft, so what fires is what was saved.
 */
private suspend fun MedicalViewModel.saveSchedule(
    draft: MedicalViewModel.MedicationDraft,
    medicationId: String,
) {
    val context: Context = getApplication()
    if (!draft.remindersEnabled || draft.times.isEmpty()) {
        draft.scheduleId?.let { DoseScheduler.cancel(context, it) }
        repository.deleteScheduleFor(medicationId)
        return
    }
    val schedule = MedicationSchedule(
        id = draft.scheduleId ?: Uuid.random().toString(),
        medicationId = medicationId,
        enabled = true,
        times = draft.times.sorted(),
        repeatUnit = draft.repeatUnit,
        interval = draft.interval.coerceAtLeast(1),
        daysOfWeek = draft.daysOfWeek,
        anchorDate = draft.anchorDate,
        endDate = draft.remindersUntil,
    )
    repository.upsertSchedule(schedule)
    DoseScheduler.arm(context, schedule)
}

fun MedicalViewModel.deleteMedication(entry: MedicationEntry) {
    viewModelScope.launch {
        repository.getScheduleFor(entry.id)?.let {
            DoseScheduler.cancel(getApplication(), it.id)
        }
        repository.deleteScheduleFor(entry.id)
        // The doses go with the medication. Leaving them would strand rows pointing at a
        // MedicationRequest that no longer exists.
        repository.deleteDosesFor(entry.id)
        repository.deleteMedication(entry)
        val dataSourceId = entry.dataSourceId
        val fhirId = entry.fhirResourceId
        if (dataSourceId != null && fhirId != null) {
            PersonalHealthRecords.delete(
                dataSourceId,
                PersonalHealthRecords.medicationRequestResourceType,
                fhirId,
            )
        }
    }
}

/**
 * Writes the medication to Health Connect as a `MedicationRequest`.
 *
 * The schedule goes with it, which is what puts the reminder times in the record as
 * `dosageInstruction.timing.repeat` rather than leaving them as a private detail of this app.
 */
private suspend fun MedicalViewModel.mirrorMedication(id: String) {
    val dataSourceId = PersonalHealthRecords.dataSourceId() ?: return
    val entry = repository.getMedication(id) ?: return
    val schedule = repository.getScheduleFor(id)
    val written = PersonalHealthRecords.upsert(
        dataSourceId,
        FhirRecords.medicationRequestJson(entry, schedule),
    ) ?: return
    repository.upsertMedication(
        entry.copy(fhirResourceId = written, dataSourceId = dataSourceId)
    )
}

/**
 * Records that a dose was taken, now.
 *
 * Called from the reminder's Taken action and from the medication list. Only acknowledged doses
 * are ever written — see [DoseEvent].
 */
fun MedicalViewModel.recordDoseTaken(medicationId: String, takenAt: Instant = Instant.now()) {
    viewModelScope.launch {
        val event = DoseEvent(
            id = Uuid.random().toString(),
            medicationId = medicationId,
            takenAt = takenAt,
        )
        repository.upsertDoseEvent(event)

        val dataSourceId = PersonalHealthRecords.dataSourceId() ?: return@launch
        val medication = repository.getMedication(medicationId) ?: return@launch
        val written = PersonalHealthRecords.upsert(
            dataSourceId,
            FhirRecords.doseEventJson(event, medication),
        ) ?: return@launch
        repository.upsertDoseEvent(
            event.copy(fhirResourceId = written, dataSourceId = dataSourceId)
        )
    }
}

fun MedicalViewModel.deleteDoseEvent(event: DoseEvent) {
    viewModelScope.launch {
        repository.deleteDoseEvent(event)
        val dataSourceId = event.dataSourceId
        val fhirId = event.fhirResourceId
        if (dataSourceId != null && fhirId != null) {
            PersonalHealthRecords.delete(
                dataSourceId,
                PersonalHealthRecords.medicationStatementResourceType,
                fhirId,
            )
        }
    }
}
