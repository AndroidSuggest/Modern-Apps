package com.vayunmathur.calendar.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Reschedules all event-reminder alarms after a reboot or app update, since
 * AlarmManager alarms don't survive either. Mirrors the clock app's
 * [com.vayunmathur.clock.util.BootReceiver] plus findfamily's
 * package-replaced handling.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                val pendingResult = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        ReminderScheduler.reconcileAll(context)
                    } catch (_: Exception) {
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
