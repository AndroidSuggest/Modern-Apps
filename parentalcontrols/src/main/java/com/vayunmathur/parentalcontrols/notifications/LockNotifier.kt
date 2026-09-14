package com.vayunmathur.parentalcontrols.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.vayunmathur.parentalcontrols.R
import com.vayunmathur.parentalcontrols.platform.Enforcer
import com.vayunmathur.parentalcontrols.ui.AppLimitLockActivity
import com.vayunmathur.parentalcontrols.ui.BaseLockActivity
import com.vayunmathur.parentalcontrols.ui.BedtimeLockActivity
import com.vayunmathur.parentalcontrols.ui.DailyLimitLockActivity
import com.vayunmathur.parentalcontrols.ui.DowntimeLockActivity
import com.vayunmathur.parentalcontrols.ui.SchoolTimeLockActivity

/**
 * Tells the child a restriction has bitten, with a tap target that explains it.
 *
 * Blocking itself is silent - the platform just refuses the launch - so without this the
 * child meets a dead icon and no reason. Each notification carries a full-screen intent to
 * the matching lock activity (which shows over the keyguard per the 0d mechanism) and a
 * content intent to the same place for the tap, so both the heads-up and the shade row land
 * on the reason, the unlock path and - for budgets - the bonus flow.
 *
 * Posted from receivers after [Enforcer.reconcile]: limit trips from `LimitReachedReceiver`,
 * window entries from `BedtimeReceiver`. Deduplicated by notification id per reason, so a
 * flapping boundary updates one row instead of stacking.
 */
object LockNotifier {

    fun notifyLocked(context: Context, reason: Enforcer.BlockReason, blockedPackage: String?) {
        val app = context.applicationContext
        ensureChannel(app)
        val activity = activityFor(reason)
        val intent = BaseLockActivity.intent(app, activity, blockedPackage).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            app,
            reason.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(app.getString(titleFor(reason)))
            .setContentText(app.getString(hintFor(reason)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        // POST_NOTIFICATIONS is a runtime permission; without it this throws, and a missing
        // notice must never break enforcement - the block itself already happened.
        runCatching { nm.notify(NOTIFICATION_ID_BASE + reason.ordinal, notification) }
    }

    fun cancel(context: Context, reason: Enforcer.BlockReason) {
        val nm = context.applicationContext.getSystemService(NotificationManager::class.java)
            ?: return
        runCatching { nm.cancel(NOTIFICATION_ID_BASE + reason.ordinal) }
    }

    private fun activityFor(reason: Enforcer.BlockReason): Class<out BaseLockActivity> =
        when (reason) {
            Enforcer.BlockReason.Bedtime -> BedtimeLockActivity::class.java
            Enforcer.BlockReason.Downtime -> DowntimeLockActivity::class.java
            Enforcer.BlockReason.SchoolTime -> SchoolTimeLockActivity::class.java
            Enforcer.BlockReason.DailyLimit -> DailyLimitLockActivity::class.java
            Enforcer.BlockReason.AppLimit -> AppLimitLockActivity::class.java
        }

    private fun titleFor(reason: Enforcer.BlockReason): Int =
        when (reason) {
            Enforcer.BlockReason.Bedtime -> R.string.lock_bedtime_title
            Enforcer.BlockReason.Downtime -> R.string.lock_downtime_title
            Enforcer.BlockReason.SchoolTime -> R.string.lock_school_title
            Enforcer.BlockReason.DailyLimit -> R.string.lock_daily_title
            Enforcer.BlockReason.AppLimit -> R.string.lock_applimit_title
        }

    private fun hintFor(reason: Enforcer.BlockReason): Int =
        when (reason) {
            Enforcer.BlockReason.Bedtime -> R.string.lock_bedtime_hint
            Enforcer.BlockReason.Downtime -> R.string.lock_downtime_hint
            Enforcer.BlockReason.SchoolTime -> R.string.lock_school_hint
            Enforcer.BlockReason.DailyLimit -> R.string.lock_daily_hint
            Enforcer.BlockReason.AppLimit -> R.string.lock_applimit_hint
        }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.lock_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.lock_channel_description)
            },
        )
    }

    private const val CHANNEL_ID = "restriction_locks"
    private const val NOTIFICATION_ID_BASE = 2_000_000
}
