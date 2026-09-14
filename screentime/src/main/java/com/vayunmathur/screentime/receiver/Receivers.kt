package com.vayunmathur.screentime.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vayunmathur.screentime.platform.Coordinator
import com.vayunmathur.screentime.platform.EXTRA_PACKAGE_NAME
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "ScreenTimeReceiver"

/**
 * Fired by `UsageStatsService` when an app has spent its timer budget.
 *
 * The platform delivers the `PendingIntent` registered with the observer, so this is the
 * moment the timer is actually reached rather than the moment it was set.
 */
class TimerReachedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: run {
            Log.w(TAG, "timer callback with no package")
            return
        }
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Coordinator(app).onTimerReached(packageName)
            } catch (t: Throwable) {
                Log.e(TAG, "could not enforce the timer for $packageName", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun intent(context: Context, packageName: String): Intent =
            Intent(context, TimerReachedReceiver::class.java)
                .putExtra(EXTRA_PACKAGE_NAME, packageName)

        /** Enforce immediately, for an app already over budget when the observer is armed. */
        fun enforceNow(context: Context, packageName: String) {
            val app = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { Coordinator(app).onTimerReached(packageName) }
                    .onFailure { Log.e(TAG, "could not enforce $packageName", it) }
            }
        }
    }
}

/** Fired at each focus / wind-down boundary. Re-arms the next one through [Coordinator]. */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Coordinator(app).reconcile()
            } catch (t: Throwable) {
                Log.e(TAG, "schedule reconcile failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, ScheduleReceiver::class.java)
    }
}

/**
 * Re-establishes enforcement after a reboot.
 *
 * Alarms and usage observers are both process- and boot-scoped, so without this a restart
 * silently ends timers and schedules until something else reconciles. `SpentState` survives
 * in SharedPreferences precisely so a reboot cannot hand back a spent budget.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Coordinator(app).reconcile()
            } catch (t: Throwable) {
                Log.e(TAG, "boot reconcile failed", t)
            } finally {
                pending.finish()
            }
        }
    }
}
