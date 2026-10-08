package com.vayunmathur.screentime.platform

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.vayunmathur.library.log.Log
import androidx.core.content.getSystemService
import com.vayunmathur.screentime.domain.HourBuckets
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.min

private const val TAG = "ScreenTimeHistory"

/** Which span the dashboard/detail chart covers. */
enum class UsagePeriod { Week, Day }

/** One bar in the usage chart: an axis label and the foreground time it represents. */
data class UsageBar(val label: String, val millis: Long)

/**
 * A computed usage series for a period plus the roll-ups the header shows.
 *
 * [bars] is 7 entries (Mon-Sun) for [UsagePeriod.Week] or 24 (hours) for [UsagePeriod.Day].
 * [averageMillis] is the daily average over the elapsed days of the week, or the hourly
 * average across the 24 hours of the day - matching the reference app's header.
 */
data class UsageHistoryData(
    val bars: List<UsageBar> = emptyList(),
    val totalMillis: Long = 0,
    val averageMillis: Long = 0,
    /** Package -> foreground millis over the whole range; empty for a single-package query. */
    val perApp: Map<String, Long> = emptyMap(),
)

/**
 * Reads multi-day and per-hour foreground history from [UsageStatsManager].
 *
 * The dashboard only ever showed today's totals; the chart needs a week of daily buckets and
 * a day of hourly buckets. Week buckets come from [UsageStatsManager.queryAndAggregateUsageStats]
 * per day (cheap, and it already backs the timers so "used" means the same everywhere); hourly
 * buckets are reconstructed from [UsageStatsManager.queryEvents] foreground/background pairs.
 *
 * Everything returns zeros when the usage-stats op is not held (see [UsageAccess]); the caller
 * renders that as the permission prompt, never as an empty chart.
 */
class UsageHistory(private val context: Context) {

    private val usage = context.getSystemService<UsageStatsManager>()
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Aggregate series across all packages for [period] anchored at [anchor]. */
    fun aggregate(period: UsagePeriod, anchor: LocalDate): UsageHistoryData =
        when (period) {
            UsagePeriod.Week -> week(anchor, forPackage = null)
            UsagePeriod.Day -> day(anchor, forPackage = null)
        }

    /** Per-package series for [period] anchored at [anchor]. [perApp] is left empty. */
    fun forPackage(period: UsagePeriod, anchor: LocalDate, packageName: String): UsageHistoryData =
        when (period) {
            UsagePeriod.Week -> week(anchor, forPackage = packageName)
            UsagePeriod.Day -> day(anchor, forPackage = packageName)
        }

    private fun week(anchor: LocalDate, forPackage: String?): UsageHistoryData {
        val usage = usage ?: return UsageHistoryData()
        if (!UsageAccess.isGranted(context)) return UsageHistoryData()
        val monday = anchor.minusDays((anchor.dayOfWeek.value - 1).toLong())
        val today = LocalDate.now()
        val bars = ArrayList<UsageBar>(DAYS_PER_WEEK)
        val perApp = HashMap<String, Long>()
        var total = 0L
        var elapsedDays = 0
        for (i in 0 until DAYS_PER_WEEK) {
            val day = monday.plusDays(i.toLong())
            val label = day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            if (day.isAfter(today)) {
                bars.add(UsageBar(label, 0L))
                continue
            }
            elapsedDays++
            val stats = aggregateDay(usage, day)
            val dayMillis = if (forPackage != null) {
                stats[forPackage] ?: 0L
            } else {
                stats.forEach { (pkg, ms) -> perApp[pkg] = (perApp[pkg] ?: 0L) + ms }
                stats.values.sum()
            }
            total += dayMillis
            bars.add(UsageBar(label, dayMillis))
        }
        val average = if (elapsedDays > 0) total / elapsedDays else 0L
        return UsageHistoryData(bars, total, average, if (forPackage == null) perApp else emptyMap())
    }

    private fun day(anchor: LocalDate, forPackage: String?): UsageHistoryData {
        val usage = usage ?: return UsageHistoryData()
        if (!UsageAccess.isGranted(context)) return UsageHistoryData()
        val dayStart = anchor.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = anchor.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val end = min(dayEnd, now)

        val hours = LongArray(HOURS_PER_DAY)
        if (end > dayStart) {
            for ((pkg, from, to) in foregroundIntervals(usage, dayStart, end)) {
                if (forPackage != null && pkg != forPackage) continue
                HourBuckets.addInterval(hours, dayStart, dayEnd, from, to)
            }
        }
        val bars = (0 until HOURS_PER_DAY).map { UsageBar(hourLabel(it), hours[it]) }

        // Per-app totals for the day list come from the aggregate query (matches "used").
        val perApp = if (forPackage == null && end > dayStart) {
            aggregateDay(usage, anchor)
        } else {
            emptyMap()
        }
        val total = if (forPackage != null) hours.sum() else perApp.values.sum()
        val average = total / HOURS_PER_DAY
        return UsageHistoryData(bars, total, average, perApp)
    }

    /** Per-package foreground millis for a single calendar day, dropping zero entries. */
    private fun aggregateDay(usage: UsageStatsManager, day: LocalDate): Map<String, Long> {
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = min(day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), System.currentTimeMillis())
        if (end <= start) return emptyMap()
        return runCatching {
            usage.queryAndAggregateUsageStats(start, end)
                .mapValues { (_, stats) -> stats.totalTimeInForeground }
                .filterValues { it > 0 }
        }.getOrElse {
            Log.status(TAG, "no usage stats for $day; is PACKAGE_USAGE_STATS granted?", it)
            emptyMap()
        }
    }

    /** Reconstructs (package, startMs, endMs) foreground intervals from the event stream. */
    @Suppress("DEPRECATION") // MOVE_TO_* still fires on some builds; handled alongside ACTIVITY_*.
    private fun foregroundIntervals(
        usage: UsageStatsManager,
        start: Long,
        end: Long,
    ): List<Triple<String, Long, Long>> {
        val events = runCatching { usage.queryEvents(start, end) }.getOrElse {
            Log.status(TAG, "no usage events; is PACKAGE_USAGE_STATS granted?", it)
            return emptyList()
        }
        val open = HashMap<String, Long>()
        val intervals = ArrayList<Triple<String, Long, Long>>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            applyEvent(event, open, intervals, start, end)
        }
        // Anything still foreground at the window edge is closed there.
        open.forEach { (pkg, from) -> intervals.add(Triple(pkg, from, end)) }
        return intervals
    }

    @Suppress("DEPRECATION")
    private fun applyEvent(
        event: UsageEvents.Event,
        open: HashMap<String, Long>,
        intervals: ArrayList<Triple<String, Long, Long>>,
        start: Long,
        end: Long,
    ) {
        val pkg = event.packageName ?: return
        val stamp = event.timeStamp
        // queryEvents may return events outside the requested window on some builds.
        // A foreground that began before [start] means the app was already in front at
        // midnight, so open it there (e.g. overnight sessions count from midnight, not
        // from yesterday); anything else out of window is dropped so another day's
        // usage can never land in this day's buckets.
        if (stamp > end) return
        val atEdge = stamp < start
        when (event.eventType) {
            UsageEvents.Event.MOVE_TO_FOREGROUND, UsageEvents.Event.ACTIVITY_RESUMED -> {
                if (atEdge) open.putIfAbsent(pkg, start) else open.putIfAbsent(pkg, stamp)
            }
            UsageEvents.Event.MOVE_TO_BACKGROUND,
            UsageEvents.Event.ACTIVITY_PAUSED,
            UsageEvents.Event.ACTIVITY_STOPPED,
            -> closeInterval(open, intervals, pkg, stamp, atEdge)
        }
    }

    private fun closeInterval(
        open: HashMap<String, Long>,
        intervals: ArrayList<Triple<String, Long, Long>>,
        pkg: String,
        stamp: Long,
        atEdge: Boolean,
    ) {
        if (atEdge) {
            open.remove(pkg)
            return
        }
        open.remove(pkg)?.let { from ->
            if (stamp > from) intervals.add(Triple(pkg, from, stamp))
        }
    }

    private fun hourLabel(hour: Int): String = when {
        hour == MIDNIGHT_HOUR -> "12 AM"
        hour == NOON_HOUR -> "12 PM"
        hour < NOON_HOUR -> "$hour AM"
        else -> "${hour - NOON_HOUR} PM"
    }

    private companion object {
        const val HOURS_PER_DAY = 24
        const val DAYS_PER_WEEK = 7
        const val MIDNIGHT_HOUR = 0
        const val NOON_HOUR = 12
    }
}
