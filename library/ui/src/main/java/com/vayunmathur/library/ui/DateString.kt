package com.vayunmathur.library.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.util.DateNameStyle
import com.vayunmathur.library.util.localizedAmPmMarker
import com.vayunmathur.library.util.localizedDayOfWeekNames
import com.vayunmathur.library.util.localizedMonthNames
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The one place dates and times get turned into display strings.
 *
 * Every app used to hand-build its own `LocalDate.Format {}` / `LocalTime.Format {}`
 * (or manual `"$day/$month"` interpolation), which meant locale-correct month and
 * weekday names, localized AM/PM markers, and the system 12/24-hour setting were
 * re-derived per app and occasionally gotten wrong. These helpers centralize the
 * calendar-date and clock-time shapes the apps actually render.
 *
 * Everything returns a [String] so the caller wraps it in its own `Text` — Material
 * in normal UI, `androidx.glance.text.Text` in a widget, or a `%s` placeholder in a
 * localized resource string.
 *
 * Locale-correct names come from
 * [com.vayunmathur.library.util.localizedMonthNames] /
 * [com.vayunmathur.library.util.localizedDayOfWeekNames] /
 * [com.vayunmathur.library.util.localizedAmPmMarker], the same ICU-backed helpers
 * kotlinx-datetime callers were already using.
 *
 * Out of scope by design: elapsed/countdown durations (timer, stopwatch, track
 * length) and relative time ("Today", "5 minutes ago") — those are not calendar
 * dates or clock times and keep their own formatters.
 */
object DateString {

    // ── Date-only forms (input LocalDate) ──────────────────────────────────

    /**
     * The locale's short numeric date — `3/14/25` in en-US, `14/03/25` in en-GB.
     *
     * The only form built on `java.time`: kotlinx-datetime has no locale-aware
     * field ordering, and `FormatStyle.SHORT` is exactly that ordering.
     */
    fun dateShort(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
            .withLocale(locale)
            .format(date.toJavaLocalDate())

    /** A long, readable date — `14 March 2025`. */
    fun dateLong(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        date.format(LocalDate.Format {
            day(Padding.NONE)
            char(' ')
            monthName(MonthNames(localizedMonthNames(DateNameStyle.FULL, locale)))
            char(' ')
            year()
        })

    /** Date with weekday and year — `Mon, Jan 3, 2025`. */
    fun dateWeekday(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        date.format(LocalDate.Format {
            dayOfWeek(DayOfWeekNames(localizedDayOfWeekNames(DateNameStyle.SHORT, locale)))
            char(','); char(' ')
            monthName(MonthNames(localizedMonthNames(DateNameStyle.SHORT, locale)))
            char(' ')
            day(Padding.NONE)
            char(','); char(' ')
            year(Padding.NONE)
        })

    /** Date with weekday, no year — `Mon, Jan 3`. */
    fun dateWeekdayNoYear(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        date.format(LocalDate.Format {
            dayOfWeek(DayOfWeekNames(localizedDayOfWeekNames(DateNameStyle.SHORT, locale)))
            char(','); char(' ')
            monthName(MonthNames(localizedMonthNames(DateNameStyle.SHORT, locale)))
            char(' ')
            day(Padding.NONE)
        })

    /** Date without a weekday — `Jan 3, 2025`. */
    fun monthDayYear(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        date.format(LocalDate.Format {
            monthName(MonthNames(localizedMonthNames(DateNameStyle.SHORT, locale)))
            char(' ')
            day(Padding.NONE)
            char(','); char(' ')
            year()
        })

    // ── Time-only forms (input LocalTime + the 12/24-hour flag) ────────────

    /** Clock time honouring the 12/24-hour setting — `3:05 PM` / `15:05`. */
    fun time(
        time: LocalTime,
        is24Hour: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String = time.format(LocalTime.Format {
        clock(is24Hour)
        if (!is24Hour) { char(' '); localizedAmPmMarker(locale) }
    })

    /** Clock time with seconds — `3:05:23 PM` / `15:05:23`. */
    fun timeSeconds(
        time: LocalTime,
        is24Hour: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String = time.format(LocalTime.Format {
        clock(is24Hour, seconds = true)
        if (!is24Hour) { char(' '); localizedAmPmMarker(locale) }
    })

    /**
     * The numeric part of the time, no AM/PM marker — `3:05` / `15:05`.
     *
     * For call sites that render the marker separately (e.g. the clock's main
     * display styles the digits and the marker differently) or concatenate it
     * themselves with custom spacing.
     */
    fun timeNumeric(time: LocalTime, is24Hour: Boolean): String =
        time.format(LocalTime.Format { clock(is24Hour) })

    /** Numeric time with seconds, no AM/PM marker — `3:05:23` / `15:05:23`. */
    fun timeSecondsNumeric(time: LocalTime, is24Hour: Boolean): String =
        time.format(LocalTime.Format { clock(is24Hour, seconds = true) })

    /** A whole-hour axis label — `3 PM` / `15:00`. */
    fun hourLabel(
        time: LocalTime,
        is24Hour: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String = time.format(LocalTime.Format {
        if (is24Hour) {
            hour(Padding.ZERO); char(':'); char('0'); char('0')
        } else {
            amPmHour(Padding.NONE); char(' '); localizedAmPmMarker(locale)
        }
    })

    /** [hourLabel] for a bare hour-of-day (0..23), for axis iteration. */
    fun hourLabel(
        hour: Int,
        is24Hour: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String = hourLabel(LocalTime(hour, 0), is24Hour, locale)

    // ── Date + time (input LocalDateTime) ──────────────────────────────────

    /** Short date and clock time together — `3/14/25 3:05 PM` / `3/14/25 15:05`. */
    fun dateTime(
        dateTime: LocalDateTime,
        is24Hour: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String = "${dateShort(dateTime.date, locale)} ${time(dateTime.time, is24Hour, locale)}"

    // ── Instant convenience overloads (the common forms) ───────────────────

    fun dateShort(
        instant: Instant,
        zone: TimeZone = TimeZone.currentSystemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = dateShort(instant.toLocalDateTime(zone).date, locale)

    fun dateLong(
        instant: Instant,
        zone: TimeZone = TimeZone.currentSystemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = dateLong(instant.toLocalDateTime(zone).date, locale)

    fun time(
        instant: Instant,
        is24Hour: Boolean,
        zone: TimeZone = TimeZone.currentSystemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = time(instant.toLocalDateTime(zone).time, is24Hour, locale)

    fun dateTime(
        instant: Instant,
        is24Hour: Boolean,
        zone: TimeZone = TimeZone.currentSystemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = dateTime(instant.toLocalDateTime(zone), is24Hour, locale)
}

// Shared "h:mm" / "HH:mm" (optionally with seconds) core so every time form
// stays consistent: 24h is zero-padded, 12h uses the non-padded am/pm hour.
private fun kotlinx.datetime.format.DateTimeFormatBuilder.WithTime.clock(
    is24Hour: Boolean,
    seconds: Boolean = false,
) {
    if (is24Hour) hour(Padding.ZERO) else amPmHour(Padding.NONE)
    char(':')
    minute()
    if (seconds) { char(':'); second() }
}

/** The device's 12/24-hour setting, for the string forms that need it. */
fun is24Hour(context: Context): Boolean = DateFormat.is24HourFormat(context)

/** [is24Hour] read from the ambient Compose context. */
@Composable
fun rememberIs24Hour(): Boolean {
    val context = LocalContext.current
    return remember { DateFormat.is24HourFormat(context) }
}
