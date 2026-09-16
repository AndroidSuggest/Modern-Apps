package com.vayunmathur.screentime.ui

import com.vayunmathur.screentime.platform.UsagePeriod
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Duration formatters shared by the dashboard, the details screen and the chart.
 *
 * [formatStat] is the big header/list style ("23h29m", "07m46s"); [formatAxis] is the compact
 * chart-axis style ("3h45m", "49m", "0h"); [formatMinutes] renders a whole-minute timer value.
 */

/** Header and list style: `HHhMMm` at an hour or more, else `MMmSSs`, else `SSs`. */
fun formatStat(millis: Long): String {
    val totalSeconds = millis / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0 -> "%02dh%02dm".format(hours, minutes)
        minutes > 0 -> "%02dm%02ds".format(minutes, seconds)
        else -> "%02ds".format(seconds)
    }
}

/** Compact chart-axis style: `3h45m` at an hour or more, `49m` under an hour, `0h` at zero. */
fun formatAxis(millis: Long): String {
    if (millis <= 0L) return "0h"
    val totalMinutes = millis / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0 && minutes > 0 -> "${hours}h${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}

/** Whole-minute timer value: `45 min` or `1 h 30 min`. */
fun formatMinutes(minutes: Long): String =
    if (minutes < 60) {
        "$minutes min"
    } else {
        val hours = minutes / 60
        val rest = minutes % 60
        if (rest == 0L) "$hours h" else "$hours h $rest min"
    }

/** Monday of the week containing [date] (weeks run Monday-Sunday). */
fun weekStart(date: LocalDate): LocalDate =
    date.minusDays((date.dayOfWeek.value - 1).toLong())

/** The date-range caption under the chart: a week span, or a single day. */
fun rangeLabel(period: UsagePeriod, anchor: LocalDate): String {
    val locale = Locale.getDefault()
    return if (period == UsagePeriod.Day) {
        anchor.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", locale))
    } else {
        val start = weekStart(anchor)
        val end = start.plusDays(6)
        val fmt = DateTimeFormatter.ofPattern("MMM d", locale)
        "${start.format(fmt)} – ${end.format(fmt)}"
    }
}
