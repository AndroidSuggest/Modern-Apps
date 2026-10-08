@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.health.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vayunmathur.library.log.Log
import com.vayunmathur.health.data.DoseEvent
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.domain.FhirRecords
import com.vayunmathur.health.notifications.DoseNotification
import com.vayunmathur.health.service.DoseSoundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Handles Taken and Snooze, from either the notification actions or the full-screen reminder.
 *
 * Both routes go through here rather than through the activity, because the notification's buttons
 * have to work when the activity was never allowed to start.
 */
class DoseActionReceiver : BroadcastReceiver() {

    // Broad catches below are deliberate: onReceive must not throw, and Room plus the
    // Health Connect client throw undocumented RuntimeExceptions (not just declared ones).
    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(context: Context, intent: Intent) {
        val scheduleId = intent.getStringExtra(DoseScheduler.EXTRA_SCHEDULE_ID) ?: return
        val medicationId = intent.getStringExtra(DoseScheduler.EXTRA_MEDICATION_ID) ?: return

        stopRinging(context)

        when (intent.action) {
            ACTION_TAKEN -> recordTaken(context, medicationId)
            ACTION_SNOOZE -> {
                DoseScheduler.armSnooze(context, scheduleId, medicationId, SNOOZE_MS)
                Log.status(TAG, "Dose for $medicationId snoozed for ${SNOOZE_MS / MS_PER_MINUTE} minutes")
            }
        }
    }

    /**
     * Writes the dose locally, then mirrors it to Health Connect.
     *
     * Local first, and the mirror is allowed to fail: the alarm may have woken a process where
     * nothing has set up the Health Connect client, and losing the record of a dose the user
     * explicitly acknowledged would be much worse than an un-mirrored row. A later import reconciles.
     */
    // Broad catch below is deliberate: the alarm may have woken a process where nothing
    // has set up the Health Connect client, and losing the record of an acknowledged dose
    // would be much worse than an un-mirrored row. A later import reconciles.
    @Suppress("TooGenericExceptionCaught")
    private fun recordTaken(context: Context, medicationId: String) {
        val repository = HealthRepository.get(context)
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val event = DoseEvent(
                    id = Uuid.random().toString(),
                    medicationId = medicationId,
                    takenAt = Instant.now(),
                )
                repository.upsertDoseEvent(event)
                Log.status(TAG, "Dose taken for $medicationId")

                val medication = repository.getMedication(medicationId) ?: return@launch
                val dataSourceId = PersonalHealthRecords.dataSourceId() ?: return@launch
                val written = PersonalHealthRecords.upsert(
                    dataSourceId,
                    FhirRecords.doseEventJson(event, medication),
                ) ?: return@launch
                repository.upsertDoseEvent(
                    event.copy(fhirResourceId = written, dataSourceId = dataSourceId)
                )
            } catch (e: Exception) {
                Log.error(TAG, "Could not record the dose for $medicationId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    // Broad catch is deliberate: losing this must not throw out of onReceive — the
    // notification is already cancelled and stopping a service that is not running
    // fails with any number of unchecked exceptions depending on platform version.
    @Suppress("TooGenericExceptionCaught")
    private fun stopRinging(context: Context) {
        DoseNotification.cancel(context)
        try {
            context.stopService(Intent(context, DoseSoundService::class.java))
        } catch (e: Exception) {
            Log.status(TAG, "Could not stop DoseSoundService", e)
        }
    }

    companion object {
        const val ACTION_TAKEN = "com.vayunmathur.health.DOSE_TAKEN"
        const val ACTION_SNOOZE = "com.vayunmathur.health.DOSE_SNOOZE"

        /** Fixed rather than configurable, which is more than this needs for now. */
        const val SNOOZE_MS = 15 * 60 * 1000L

        private const val TAG = "DoseActionReceiver"
        private const val MS_PER_MINUTE = 60_000
    }
}
