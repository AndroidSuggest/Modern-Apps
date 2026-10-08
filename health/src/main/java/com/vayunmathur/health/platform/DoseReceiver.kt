package com.vayunmathur.health.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vayunmathur.library.log.Log
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.notifications.DoseNotification
import com.vayunmathur.health.service.DoseSoundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires when a dose falls due: posts the alert, starts the sound, and re-arms the next one.
 *
 * The ordering matters and is the clock's. The notification goes out first because it is what
 * actually rings; the service and the activity are both allowed to fail without taking the ring
 * with them.
 */
class DoseReceiver : BroadcastReceiver() {

    // Broad catch below is deliberate: onReceive must not throw, and Room plus the
    // scheduler throw undocumented RuntimeExceptions (not just SQLiteException).
    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(context: Context, intent: Intent) {
        val scheduleId = intent.getStringExtra(DoseScheduler.EXTRA_SCHEDULE_ID) ?: return
        val medicationId = intent.getStringExtra(DoseScheduler.EXTRA_MEDICATION_ID) ?: return
        Log.status(TAG, "Dose due for medication $medicationId (schedule $scheduleId)")

        val repository = HealthRepository.get(context)
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val medication = repository.getMedication(medicationId)
                val name = medication?.let {
                    listOfNotNull(it.displayName, it.strength).joinToString(" ")
                } ?: return@launch

                DoseNotification.post(context, scheduleId, medicationId, name)

                // Re-arm before anything else can fail. A missed re-arm silently ends the
                // schedule, which is far worse than a reminder that fails to make a noise.
                repository.getSchedule(scheduleId)?.let { DoseScheduler.arm(context, it) }

                startSound(context, scheduleId, medicationId, name)
            } catch (e: Exception) {
                Log.error(TAG, "Could not handle the dose for $medicationId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    // Broad catch is deliberate: losing this must not throw out of onReceive — the
    // insistent notification is already posted and is the thing that rings, so a refused
    // service start is survivable. ForegroundServiceStartNotAllowedException and
    // SecurityException are the expected modes, but any failure lands in the same place.
    @Suppress("TooGenericExceptionCaught")
    private fun startSound(
        context: Context,
        scheduleId: String,
        medicationId: String,
        name: String,
    ) {
        val serviceIntent = Intent(context, DoseSoundService::class.java).apply {
            putExtra(DoseScheduler.EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(DoseScheduler.EXTRA_MEDICATION_ID, medicationId)
            putExtra(DoseSoundService.EXTRA_MEDICATION_NAME, name)
        }
        try {
            context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            Log.error(TAG, "Could not start DoseSoundService", e)
        }
    }

    private companion object {
        const val TAG = "DoseReceiver"
    }
}
