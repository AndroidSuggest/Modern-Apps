package com.vayunmathur.screentime.platform

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import androidx.core.content.getSystemService
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
        val bars = ArrayList<UsageBar>(7)
        val perApp = HashMap<String, Long>()
        var total = 0L
        var elapsedDays = 0
        for (i in 0 until 7) {
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

        val hours = LongArray(24)
        if (end > dayStart) {
            for ((pkg, from, to) in foregroundIntervals(usage, dayStart, end)) {
                if (forPackage != null && pkg != forPackage) continue
                addToHourBuckets(hours, dayStart, from, to)
            }
        }
        val bars = (0 until 24).map { UsageBar(hourLabel(it), hours[it]) }

        // Per-app totals for the day list come from the aggregate query (matches "used").
        val perApp = if (forPackage == null && end > dayStart) {
            aggregateDay(usage, anchor)
        } else {
            emptyMap()
        }
        val total = if (forPackage != null) hours.sum() else perApp.values.sum()
        val average = total / 24
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
            Log.w(TAG, "no usage stats for $day; is PACKAGE_USAGE_STATS granted?", it)
            emptyMap()
        }
    }

    /** Reconstructs (package, startMs, endMs) foreground intervals from the event stream. */
    private fun foregroundIntervals(
        usage: UsageStatsManager,
        start: Long,
        end: Long,
    ): List<Triple<String, Long, Long>> {
        val events = runCatching { usage.queryEvents(start, end) }.getOrElse {
            Log.w(TAG, "no usage events; is PACKAGE_USAGE_STATS granted?", it)
            return emptyList()
        }
        val open = HashMap<String, Long>()
        val intervals = ArrayList<Triple<String, Long, Long>>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED ->
                    open.putIfAbsent(pkg, event.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                    open.remove(pkg)?.let { from -> intervals.add(Triple(pkg, from, event.timeStamp)) }
            }
        }
        // Anything still foreground at the window edge is closed there.
        open.forEach { (pkg, from) -> intervals.add(Triple(pkg, from, end)) }
        return intervals
    }

    /** Splits [from, to] into per-hour contributions relative to [dayStart]. */
    private fun addToHourBuckets(hours: LongArray, dayStart: Long, from: Long, to: Long) {
        var cursor = from.coerceAtLeast(dayStart)
        val stop = to.coerceAtMost(dayStart + 24L * 3_600_000L)
        while (cursor < stop) {
            val hourIndex = ((cursor - dayStart) / 3_600_000L).toInt().coerceIn(0, 23)
            val hourEnd = dayStart + (hourIndex + 1) * 3_600_000L
            val slice = min(stop, hourEnd) - cursor
            hours[hourIndex] += slice
            cursor += slice
        }
    }

    private fun hourLabel(hour: Int): String = when {
        hour == 0 -> "12 AM"
        hour == 12 -> "12 PM"
        hour < 12 -> "$hour AM"
        else -> "${hour - 12} PM"
    }
}
