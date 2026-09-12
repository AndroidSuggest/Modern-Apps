@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.health.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
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

    override fun onReceive(context: Context, intent: Intent) {
        val scheduleId = intent.getStringExtra(DoseScheduler.EXTRA_SCHEDULE_ID) ?: return
        val medicationId = intent.getStringExtra(DoseScheduler.EXTRA_MEDICATION_ID) ?: return

        stopRinging(context)

        when (intent.action) {
            ACTION_TAKEN -> recordTaken(context, medicationId)
            ACTION_SNOOZE -> {
                DoseScheduler.armSnooze(context, scheduleId, medicationId, SNOOZE_MS)
                Log.i(TAG, "Dose for $medicationId snoozed for ${SNOOZE_MS / 60_000} minutes")
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
                Log.i(TAG, "Dose taken for $medicationId")

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
                Log.e(TAG, "Could not record the dose for $medicationId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun stopRinging(context: Context) {
        DoseNotification.cancel(context)
        try {
            context.stopService(Intent(context, DoseSoundService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop DoseSoundService", e)
        }
    }

    companion object {
        const val ACTION_TAKEN = "com.vayunmathur.health.DOSE_TAKEN"
        const val ACTION_SNOOZE = "com.vayunmathur.health.DOSE_SNOOZE"

        /** Fixed rather than configurable, which is more than this needs for now. */
        const val SNOOZE_MS = 15 * 60 * 1000L

        private const val TAG = "DoseActionReceiver"
    }
}
