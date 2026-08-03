package com.vayunmathur.appstore.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vayunmathur.appstore.MainActivity
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.play.PlayRepository
import com.vayunmathur.library.room.buildDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.TimeUnit

/**
 * Checks for updates in the background and says so once, quietly.
 *
 * The store used to require two manual taps — "Sync F-Droid", then "Check Play" — which
 * is not something anyone remembers to do, so an app installed here would sit on a stale
 * version indefinitely. This refreshes the offline catalogues, asks Play about the
 * packages neither of them lists, and posts a notification only when the set of available
 * updates has actually changed since the last notification.
 *
 * It deliberately does not install anything. Every install path in this store shows a
 * verification outcome and, on most devices, a system confirmation dialog; doing that
 * unattended would either bury the user in prompts or hide the outcome.
 */
class UpdateCheckWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val scope = CoroutineScope(SupervisorJob())
        val db = context.buildDatabase<AppDatabase>(
            dbName = DB_NAME,
            migrations = AppDatabase.migrations,
        )
        val catalog = CatalogRepository(context, db, scope)
        val installedRepo = InstalledAppsRepository(context)
        val play = PlayRepository(context)

        catalog.sync()
        installedRepo.refresh()
        play.restore()

        val installed = installedRepo.apps.value
        val fromCatalog = catalog.updatesFor(installed)

        // Only Play can answer for packages the offline catalogues have never heard of.
        val index = catalog.packageIndex.value
        val unknown = installed.filter { it.packageName !in index }.map { it.packageName }
        val remote = play.details(unknown).associateBy { it.packageName }
        val fromPlay = installed.mapNotNull { inst ->
            remote[inst.packageName]?.takeIf { it.versionCode > inst.versionCode }
        }

        val updates = (fromCatalog + fromPlay).distinctBy { it.packageName }
        notifyIfChanged(updates.map { it.packageName }.toSortedSet(), updates.size)

        scope.cancel()
        Result.success()
    } catch (e: Exception) {
        Log.w(TAG, "Update check failed", e)
        Result.retry()
    }

    /**
     * Notify only when the set of updatable packages differs from last time.
     *
     * Without this, a daily check on a phone with one perpetually-stale app would post the
     * same notification every day until the user turned notifications off.
     */
    private fun notifyIfChanged(packages: Set<String>, count: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val signature = packages.joinToString(",")
        if (prefs.getString(KEY_LAST_NOTIFIED, "") == signature) return
        prefs.edit().putString(KEY_LAST_NOTIFIED, signature).apply()

        if (count == 0) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        createChannel()
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(
                context.resources.getQuantityString(R.plurals.updates_count, count, count)
            )
            .setContentText(context.getString(R.string.updates_notification_body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.updates_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.updates_channel_description) }
        )
    }

    companion object {
        private const val TAG = "UpdateCheckWorker"
        private const val CHANNEL_ID = "appstore-updates"
        private const val NOTIFICATION_ID = 4201
        private const val PREFS = "appstore-update-check"
        private const val KEY_LAST_NOTIFIED = "last_notified_packages"

        /**
         * Twelve hours, and no immediate run.
         *
         * A sync pulls F-Droid's reproducibility feed and signed index — tens of megabytes
         * — so kicking one off every time the activity starts would be a lot of somebody's
         * data allowance. `KEEP` leaves an already-scheduled chain alone, and the periodic
         * request only fires on an unmetered connection.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        private const val WORK_NAME = "AppStoreUpdateCheck"
    }
}
