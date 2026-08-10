package com.vayunmathur.health.util

import com.vayunmathur.library.ui.DateString
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.format
import kotlinx.datetime.format.Padding
import kotlin.time.Duration.Companion.minutes

fun LocalDate.displayString() = DateString.monthDayYear(this)

private val time24Format = LocalTime.Format {
    hour(Padding.NONE)
    chars(":")
    minute()
}

/** "h:mm AM/PM", e.g. "7:05 AM". */
fun formatTimeAmPm(time: LocalTime): String = DateString.time(time, is24Hour = false)

/** "h AM/PM" for a whole hour, e.g. "7 AM". */
fun formatHourAmPm(hour: Int): String = DateString.hourLabel(hour, is24Hour = false)

/** 24-hour "H:mm", e.g. "22:30". */
fun formatTime24(time: LocalTime): String = time.format(time24Format)

fun formatDuration(minutes: Long): String =
    minutes.minutes.toComponents { h, m, _, _ ->
        if (h > 0) "${h}h ${m}m" else "${m}m"
    }
