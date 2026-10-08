package com.vayunmathur.health.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vayunmathur.library.log.Log
import com.vayunmathur.health.data.HealthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-arms every enabled dose schedule after a reboot or a time change.
 *
 * `ACTION_BOOT_COMPLETED` only, unlike the clock's equivalent which also handles
 * `ACTION_LOCKED_BOOT_COMPLETED`. `ClockRepository` is built with `useDeviceProtectedStorage = true`
 * so its alarms survive a reboot before unlock; `HealthRepository` deliberately is not, because
 * health records belong in credential-protected storage. The consequence is that a dose due between
 * a reboot and the first unlock is missed, which is the right trade for medical data.
 *
 * Also listens for time and time-zone changes: an alarm armed for a wall-clock instant in the old
 * zone is wrong in the new one, and the interval arithmetic has to be redone.
 */
class HealthBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED -> reArm(context, intent.action.orEmpty())
        }
    }

    // Broad catch below is deliberate: onReceive must not throw, and Room plus the
    // scheduler throw undocumented RuntimeExceptions (not just SQLiteException).
    @Suppress("TooGenericExceptionCaught")
    private fun reArm(context: Context, action: String) {
        val repository = HealthRepository.get(context)
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val schedules = repository.getEnabledSchedules()
                schedules.forEach { DoseScheduler.arm(context, it) }
                Log.status(TAG, "$action: re-armed ${schedules.size} schedule(s)")
            } catch (e: Exception) {
                Log.error(TAG, "$action: could not re-arm dose schedules", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "HealthBootReceiver"
    }
}
