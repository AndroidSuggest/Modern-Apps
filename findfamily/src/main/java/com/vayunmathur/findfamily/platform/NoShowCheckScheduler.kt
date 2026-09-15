package com.vayunmathur.findfamily.platform

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.NoShowAlert
import com.vayunmathur.findfamily.domain.NoShowPolicy
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Scheduler + worker for no-show alerts (issue 702).
 *
 * Each alert gets one inexact [androidx.work.OneTimeWorkRequest] firing at its
 * deadline ([NoShowAlert.expectedAt] + [NoShowAlert.grace]), enqueued as unique
 * work per alert id so re-arming replaces rather than duplicates. Deliberately
 * inexact WorkManager only — no `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`.
 *
 * The actual due/arrival decision lives in [NoShowPolicy] (pure, unit-tested);
 * this file is only the Android glue: enqueue at the deadline, sweep when the
 * work runs, and re-enqueue everything after reboot / process init.
 *
 * User-visible notification delivery is NOT here — that is wired separately
 * (NO_SHOW channel step), consuming the claimed alerts [sweepNow] returns.
 */
class NoShowCheckWorker(
    appContext: Context,
    workerParams: androidx.work.WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = try {
        val fired = NoShowCheckScheduler.sweepNow(applicationContext)
        if (fired.isNotEmpty()) {
            Log.i(TAG, "sweep fired ${fired.size} alert(s): ${fired.map { it.id }}")
        }
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        private const val TAG = "FF-NoShow"
        internal const val KEY_ALERT_ID = "alert_id"
    }
}

object NoShowCheckScheduler {

    private const val TAG = "FF-NoShow"

    private fun workName(alertId: Long) = "noshow-$alertId"

    /**
     * Enqueue (or replace) the one-shot check for [alert] at its deadline.
     * Past-due deadlines enqueue with zero delay so they sweep immediately.
     */
    fun schedule(context: Context, alert: NoShowAlert) {
        val deadline = NoShowPolicy.deadline(alert.expectedAt, alert.grace)
        val delayMs = (deadline - Clock.System.now()).inWholeMilliseconds.coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<NoShowCheckWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(NoShowCheckWorker.KEY_ALERT_ID to alert.id))
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(workName(alert.id), ExistingWorkPolicy.REPLACE, request)
    }

    /** Drop the pending check for [alertId], if any (e.g. alert deleted). */
    fun cancel(context: Context, alertId: Long) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(alertId))
    }

    /**
     * Check every due alert against the latest fixes and claim the ones that
     * fire. Callable from the heartbeat / service loop as well as the worker.
     *
     * Claiming is atomic via `markFired` (0/1 rows), so concurrent sweeps
     * cannot double-fire. One-shot rows are deleted after claiming; recurring
     * rows stay with `fired = true` until re-armed. Returns the claimed
     * alerts for the notification step to deliver.
     */
    suspend fun sweepNow(context: Context): List<NoShowAlert> {
        val repository = FindFamilyRepository.get(context.applicationContext)
        val now = Clock.System.now()
        val due = repository.getDueNoShowAlerts(now.epochSeconds)
        if (due.isEmpty()) return emptyList()
        val latestByUser = repository.latestLocationsOnce().associateBy { it.userid }
        val fired = mutableListOf<NoShowAlert>()
        for (alert in due) {
            val fix = latestByUser[alert.watchedUserId]
            val arrived = isArrived(repository, alert, fix)
            if (!NoShowPolicy.shouldFire(
                    fired = alert.fired,
                    expectedAt = alert.expectedAt,
                    grace = alert.grace,
                    now = now,
                    arrived = arrived,
                )
            ) continue
            // Claim first: only the sweep whose UPDATE matches notifies.
            if (repository.markNoShowAlertFired(alert.id) != 1) continue
            if (alert.oneShot) runCatching { repository.deleteNoShowAlert(alert) }
            fired.add(alert.copy(fired = true))
        }
        return fired
    }

    private suspend fun isArrived(
        repository: FindFamilyRepository,
        alert: NoShowAlert,
        fix: com.vayunmathur.findfamily.data.LocationValue?,
    ): Boolean {
        if (fix == null) return false
        val waypointId = alert.waypointId
        if (waypointId == null) {
            return NoShowPolicy.hasAnyFreshFix(fix.timestamp, alert.expectedAt)
        }
        val waypoint = runCatching { repository.getWaypoint(waypointId) }.getOrNull()
            ?: return false
        return NoShowPolicy.hasArrivedAtWaypoint(
            fixLat = fix.coord.lat,
            fixLon = fix.coord.lon,
            fixAccuracyMeters = fix.acc.toDouble(),
            fixTimestamp = fix.timestamp,
            expectedAt = alert.expectedAt,
            waypointLat = waypoint.coord.lat,
            waypointLon = waypoint.coord.lon,
            waypointRangeMeters = waypoint.range,
        )
    }

    /**
     * Re-enqueue checks for every unfired alert. Call from `BootReceiver` and
     * VM / process init — the mirror of `applyDueAutoToggles`, which the
     * heartbeat re-applies the same way rather than trusting settled state.
     */
    suspend fun rescheduleAll(context: Context) {
        val repository = FindFamilyRepository.get(context.applicationContext)
        val pending = repository.getAllNoShowAlerts().filter { !it.fired }
        for (alert in pending) {
            runCatching { schedule(context, alert) }
                .onFailure { e -> Log.w(TAG, "reschedule alert ${alert.id} failed", e) }
        }
        Log.i(TAG, "rescheduled ${pending.size} no-show alert(s)")
    }

    /** Convenience: schedule exactly at the deadline computed from these parts. */
    fun scheduleAt(
        context: Context,
        alertId: Long,
        expectedAt: kotlin.time.Instant,
        grace: Duration,
    ) {
        val now = Clock.System.now()
        val delayMs = ((expectedAt + grace) - now).inWholeMilliseconds.coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<NoShowCheckWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(NoShowCheckWorker.KEY_ALERT_ID to alertId))
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(workName(alertId), ExistingWorkPolicy.REPLACE, request)
    }
}
