package com.vayunmathur.email.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.vayunmathur.email.data.EmailSyncWorker
import com.vayunmathur.email.data.ImapIdleService
import com.vayunmathur.email.data.OutboxSendWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * After reboot or app update:
 * - Purges legacy 15-min full poll, re-schedules hourly non-INBOX poll.
 * - Runs a one-off catch-up for folder discovery + non-INBOX.
 * - Restarts outbox retry chain and IDLE push for INBOX (no-op if no accounts).
 *
 * On Android 12+ (S) starting a FGS with type dataSync from BOOT_COMPLETED is
 * NOT allowed (ForegroundServiceStartNotAllowedException). We must not call
 * startForegroundService from boot on S+. Instead we schedule safe WorkManager
 * work and set a flag `idle_restart_pending` so [ImapIdleService] is started
 * when the app next comes to foreground (MainActivity.onStart) or via
 * [com.vayunmathur.email.data.ImapIdleRetryWorker].
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                val pending = goAsync()
                val appContext = context.applicationContext
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        Log.d(TAG, "Boot (${intent.action}): restarting hourly + one-off + outbox")
                        EmailSyncWorker.scheduleHourlyNonInboxSync(appContext)
                        EmailSyncWorker.runOneOffSync(appContext)
                        OutboxSendWorker.runNow(appContext)

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            // Do NOT start FGS from boot on S+ — it will crash with
                            // ForegroundServiceStartNotAllowedException for dataSync.
                            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                .edit().putBoolean(KEY_PENDING, true).apply()
                            Log.d(TAG, "S+ boot: deferred IDLE start, set $KEY_PENDING=true")
                        } else {
                            try {
                                ImapIdleService.start(appContext)
                            } catch (t: Throwable) {
                                Log.w(TAG, "Boot IDLE start failed (pre-S): ${t.message}", t)
                                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                    .edit().putBoolean(KEY_PENDING, true).apply()
                            }
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "BootReceiver failed: ${t.message}", t)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "EmailBoot"
        const val PREFS = "email_idle_state"
        const val KEY_PENDING = "idle_restart_pending"
    }
}
