package com.vayunmathur.screentime.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.core.content.getSystemService
import com.vayunmathur.screentime.data.WindDownSchedule
import com.vayunmathur.screentime.receiver.ScheduleReceiver
import java.time.LocalDateTime
import java.time.ZoneId

private const val TAG = "ScreenTimeScheduler"

/**
 * Arms the next wind-down boundary.
 *
 * One alarm for its next transition in either direction, re-armed every time it fires (all
 * receivers funnel through the coordinator, which calls back here).
 *
 * Same minute-scan predicate-sharing as parental controls' `BedtimeScheduler`: the boundary
 * search evaluates the exact `contains` predicate the enforcer uses, so the two cannot
 * disagree about when a window opens. Exact alarms - a schedule is a promise about a
 * specific minute - degrading to inexact when the permission is revoked.
 */
class WindowScheduler(private val context: Context) {

    private val alarms = context.getSystemService<AlarmManager>()

    fun armAll(windDown: WindDownSchedule) {
        armWindow(windDown.enabled, windDown::activeAt, REQUEST_WINDDOWN)
    }

    private fun armWindow(
        enabled: Boolean,
        activeAt: (LocalDateTime) -> Boolean,
        requestCode: Int,
    ) {
        val alarms = alarms ?: return
        val pending = pendingIntent(requestCode)
        alarms.cancel(pending)
        if (!enabled) return

        val next = nextBoundary(activeAt) ?: return
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val canExact = alarms.canScheduleExactAlarms()
        runCatching {
            if (canExact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                Log.w(TAG, "no exact-alarm permission; window boundary will be approximate")
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }.onFailure { Log.w(TAG, "could not arm the window alarm", it) }
    }

    private fun nextBoundary(activeAt: (LocalDateTime) -> Boolean): LocalDateTime? {
        val now = LocalDateTime.now().withSecond(0).withNano(0)
        var state = activeAt(now)
        for (step in 1..MINUTES_IN_WEEK) {
            val at = now.plusMinutes(step.toLong())
            val next = activeAt(at)
            if (next != state) return at
            state = next
        }
        return null
    }

    private fun pendingIntent(requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        ScheduleReceiver.intent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val REQUEST_WINDDOWN = 12
        const val MINUTES_IN_WEEK = 7 * 24 * 60
    }
}

/** Whether wind-down is active at [at], in the device's current timezone. */
fun WindDownSchedule.activeAt(at: LocalDateTime): Boolean {
    if (!enabled) return false
    val minute = at.hour * 60 + at.minute
    val day = at.dayOfWeek.value - 1
    val set = (daysMask shr day) and 1 == 1
    if (!set) return false
    return if (startMinute <= endMinute) {
        minute >= startMinute && minute < endMinute
    } else {
        (minute >= startMinute) || (minute < endMinute && (daysMask shr ((day + 6) % 7)) and 1 == 1)
    }
}
