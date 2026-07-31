package com.vayunmathur.health.util

import com.vayunmathur.library.util.localizedAmPmMarker
import kotlinx.datetime.format.DateTimeFormat
import com.vayunmathur.library.util.DateNameStyle
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import com.vayunmathur.library.util.localizedMonthNames
import kotlin.time.Duration.Companion.minutes

fun LocalDate.displayString() = this.format(LocalDate.Format {
    monthName(MonthNames(localizedMonthNames(DateNameStyle.SHORT)))
    chars(" ")
    day(Padding.NONE)
    chars(", ")
    year()
})

private val timeAmPmFormat: DateTimeFormat<LocalTime> get() = LocalTime.Format {
    amPmHour(Padding.NONE)
    chars(":")
    minute()
    chars(" ")
    localizedAmPmMarker()
}

private val hourAmPmFormat: DateTimeFormat<LocalTime> get() = LocalTime.Format {
    amPmHour(Padding.NONE)
    chars(" ")
    localizedAmPmMarker()
}

private val time24Format = LocalTime.Format {
    hour(Padding.NONE)
    chars(":")
    minute()
}

/** "h:mm AM/PM", e.g. "7:05 AM". */
fun formatTimeAmPm(time: LocalTime): String = time.format(timeAmPmFormat)

/** "h AM/PM" for a whole hour, e.g. "7 AM". */
fun formatHourAmPm(hour: Int): String = LocalTime(hour, 0).format(hourAmPmFormat)

/** 24-hour "H:mm", e.g. "22:30". */
fun formatTime24(time: LocalTime): String = time.format(time24Format)

fun formatDuration(minutes: Long): String =
    minutes.minutes.toComponents { h, m, _, _ ->
        if (h > 0) "${h}h ${m}m" else "${m}m"
    }
