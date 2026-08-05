package com.vayunmathur.passwords.sync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * WorkManager scheduling for the kdbx sync.
 *
 * Deliberately constraint-free: the document is usually a local file, so requiring a
 * network would stop it syncing offline. A cloud-backed document that cannot be opened
 * is handled as the normal "cannot read" abort instead.
 */
object KdbxSyncScheduler {
    private const val PERIODIC_WORK = "KdbxSyncPeriodic"
    private const val IMMEDIATE_WORK = "KdbxSyncNow"
    private const val DEBOUNCED_WORK = "KdbxSyncAfterEdit"
    private const val DEBOUNCE_SECONDS = 10L

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<KdbxSyncWorker>(
            15, TimeUnit.MINUTES,
            5, TimeUnit.MINUTES,
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request,
        )
    }

    fun syncNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<KdbxSyncWorker>().build(),
        )
    }

    /** Coalesces a burst of local edits: REPLACE on a delayed unique request is the debounce. */
    fun scheduleDebounced(context: Context) {
        val request = OneTimeWorkRequestBuilder<KdbxSyncWorker>()
            .setInitialDelay(DEBOUNCE_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            DEBOUNCED_WORK, ExistingWorkPolicy.REPLACE, request,
        )
    }

    fun cancel(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(PERIODIC_WORK)
        workManager.cancelUniqueWork(IMMEDIATE_WORK)
        workManager.cancelUniqueWork(DEBOUNCED_WORK)
    }
}
