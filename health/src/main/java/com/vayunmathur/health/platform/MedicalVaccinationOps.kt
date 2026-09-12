package com.vayunmathur.health.platform

import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import com.vayunmathur.health.data.MedicalAttachment
import com.vayunmathur.health.data.VaccinationEntry
import com.vayunmathur.health.domain.FhirRecords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Saves the vaccination form, whether adding or editing, then mirrors it.
 *
 * Newly picked attachments are imported here rather than when the user picked them, so
 * abandoning the form cannot leave orphaned files behind; removed ones are deleted here for the
 * mirror-image reason. [fallbackAttachmentName] covers a content provider that reports no
 * display name.
 */
fun MedicalViewModel.saveVaccinationDraft(fallbackAttachmentName: String) {
    val draft = _vaccinationDraft.value
    if (draft.displayName.isBlank()) return

    viewModelScope.launch {
        // Keeping the row id on edit is what makes the Health Connect mirror an update rather
        // than a second resource, since the row id is also the FHIR resource id.
        val existing = draft.editingId?.let { repository.getVaccination(it) }
        val entry = (existing ?: VaccinationEntry(
            id = Uuid.random().toString(),
            displayName = "",
            occurredAt = Instant.now(),
        )).copy(
            cvxCode = draft.cvxCode,
            displayName = draft.displayName.trim(),
            occurredAt = draft.occurredOn.toInstant(),
            lotNumber = draft.lotNumber.blankToNull(),
            site = draft.site.blankToNull(),
            route = draft.route.blankToNull(),
            doseQuantity = draft.dose.blankToNull(),
            performer = draft.performer.blankToNull(),
            note = draft.note.blankToNull(),
        )
        repository.upsertVaccination(entry)

        draft.savedAttachments
            .filter { it.id in draft.removedAttachmentIds }
            .forEach { attachment ->
                AttachmentStore.delete(getApplication(), attachment.fileName)
                repository.deleteAttachment(attachment)
            }

        val stored = withContext(Dispatchers.IO) {
            draft.attachmentUris.mapNotNull { uri ->
                AttachmentStore.import(getApplication(), uri.toUri(), fallbackAttachmentName)
            }
        }
        val now = Instant.now()
        stored.forEach { imported ->
            repository.insertAttachment(
                MedicalAttachment(
                    id = Uuid.random().toString(),
                    vaccinationId = entry.id,
                    fileName = imported.fileName,
                    displayName = imported.displayName,
                    mimeType = imported.mimeType,
                    sizeBytes = imported.sizeBytes,
                    importedAt = now,
                )
            )
        }

        mirrorVaccination(entry.id)
    }
}

fun MedicalViewModel.deleteVaccination(entry: VaccinationEntry) {
    viewModelScope.launch {
        repository.getAttachmentsFor(entry.id).forEach { attachment ->
            AttachmentStore.delete(getApplication(), attachment.fileName)
            repository.deleteAttachment(attachment)
        }
        repository.deleteVaccination(entry)

        val dataSourceId = entry.dataSourceId
        val fhirId = entry.fhirResourceId
        if (dataSourceId != null && fhirId != null) {
            PersonalHealthRecords.delete(
                dataSourceId,
                PersonalHealthRecords.immunizationResourceType,
                fhirId,
            )
        }
    }
}

private suspend fun MedicalViewModel.mirrorVaccination(id: String) {
    val dataSourceId = PersonalHealthRecords.dataSourceId() ?: return
    val entry = repository.getVaccination(id) ?: return
    val fhirAttachments = repository.getAttachmentsFor(id).map { attachment ->
        FhirRecords.FhirAttachment(
            contentType = attachment.mimeType,
            title = attachment.displayName,
            url = AttachmentStore.fileFor(getApplication(), attachment.fileName).toURI().toString(),
            creation = attachment.importedAt,
        )
    }
    val written = PersonalHealthRecords.upsert(
        dataSourceId,
        FhirRecords.immunizationJson(entry, fhirAttachments),
    ) ?: return
    repository.upsertVaccination(
        entry.copy(fhirResourceId = written, dataSourceId = dataSourceId)
    )
}
