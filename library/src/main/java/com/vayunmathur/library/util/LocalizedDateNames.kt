package com.vayunmathur.library.util

import android.icu.text.DateFormatSymbols
import android.icu.util.Calendar
import android.icu.util.GregorianCalendar
import androidx.core.text.util.LocalePreferences
import kotlinx.datetime.format.DateTimeFormatBuilder
import java.util.Locale

/**
 * Locale-aware date-name helpers.
 *
 * kotlinx-datetime's `MonthNames.ENGLISH_*` / `DayOfWeekNames.ENGLISH_*` constants
 * hard-code English names regardless of the device locale. These helpers return the
 * localized names for the current (or given) locale so callers can build
 * `MonthNames(localizedMonthNames(...))` / `DayOfWeekNames(localizedDayOfWeekNames(...))`.
 *
 * Backed by ICU, which is the same data source `java.time`'s `getDisplayName` reads
 * from on Android.
 */

/** Width of a localized date name. */
enum class DateNameStyle(internal val icuWidth: Int) {
    /** Abbreviated, e.g. "Jan" / "Mon". */
    SHORT(DateFormatSymbols.ABBREVIATED),

    /** Full, e.g. "January" / "Monday". */
    FULL(DateFormatSymbols.WIDE),

    /** Single-letter, e.g. "J" / "M". Not unique across a week in most locales. */
    NARROW(DateFormatSymbols.NARROW),
}

// FORMAT rather than STANDALONE: that is the context java.time's plain TextStyle.SHORT /
// TextStyle.FULL resolved to, so inflected locales keep the wording they had before.
private const val CONTEXT = DateFormatSymbols.FORMAT

private const val MONTH_COUNT = 12
private const val DAYS_IN_WEEK = 7

// Pin the calendar to Gregorian. DateFormatSymbols.getInstance(locale) would follow the
// locale's *preferred* calendar (islamic-umalqura for ar-SA, persian for fa-IR, ...) and hand
// back that calendar's month names, which would then be used to label Gregorian dates.
private fun symbols(locale: Locale) = DateFormatSymbols(GregorianCalendar::class.java, locale)

/** Localized month names, January..December (index 0 == January). */
fun localizedMonthNames(
    style: DateNameStyle,
    locale: Locale = Locale.getDefault(),
): List<String> =
    symbols(locale).getMonths(CONTEXT, style.icuWidth).take(MONTH_COUNT)

/**
 * Localized AM/PM markers as `am to pm` — "AM"/"PM" in en, "ص"/"م" in ar, "午前"/"午後" in ja.
 * Pass straight into kotlinx-datetime's `amPmMarker(am, pm)`.
 */
fun localizedAmPmMarkers(locale: Locale = Locale.getDefault()): Pair<String, String> {
    val markers = symbols(locale).amPmStrings
    return markers[0] to markers[1]
}

/**
 * Locale-aware replacement for a hard-coded `amPmMarker("AM", "PM")` in a kotlinx-datetime
 * time format. Set [lowercase] for the compact "3:05 pm" style.
 */
fun DateTimeFormatBuilder.WithTime.localizedAmPmMarker(
    locale: Locale = Locale.getDefault(),
    lowercase: Boolean = false,
) {
    val (am, pm) = localizedAmPmMarkers(locale)
    if (lowercase) amPmMarker(am.lowercase(locale), pm.lowercase(locale))
    else amPmMarker(am, pm)
}

/** Localized day-of-week names, Monday..Sunday (ISO order, matching kotlinx-datetime). */
fun localizedDayOfWeekNames(
    style: DateNameStyle,
    locale: Locale = Locale.getDefault(),
): List<String> {
    // ICU indexes weekdays by Calendar.SUNDAY(1)..Calendar.SATURDAY(7); slot 0 is unused.
    val weekdays = symbols(locale).getWeekdays(CONTEXT, style.icuWidth)
    return isoWeekdays.map { weekdays[it] }
}

/** ISO day numbers for each weekday, Monday = 1 .. Sunday = 7. */
private val isoWeekdays = listOf(
    Calendar.MONDAY,
    Calendar.TUESDAY,
    Calendar.WEDNESDAY,
    Calendar.THURSDAY,
    Calendar.FRIDAY,
    Calendar.SATURDAY,
    Calendar.SUNDAY,
)

/**
 * The locale's first day of the week as an ISO day number (Monday = 1 .. Sunday = 7),
 * so it lines up with kotlinx-datetime's `isoDayNumber`.
 */
fun localeFirstDayOfWeek(locale: Locale = Locale.getDefault()): Int =
    when (LocalePreferences.getFirstDayOfWeek(locale)) {
        LocalePreferences.FirstDayOfWeek.MONDAY -> ISO_MONDAY
        LocalePreferences.FirstDayOfWeek.TUESDAY -> ISO_TUESDAY
        LocalePreferences.FirstDayOfWeek.WEDNESDAY -> ISO_WEDNESDAY
        LocalePreferences.FirstDayOfWeek.THURSDAY -> ISO_THURSDAY
        LocalePreferences.FirstDayOfWeek.FRIDAY -> ISO_FRIDAY
        LocalePreferences.FirstDayOfWeek.SATURDAY -> ISO_SATURDAY
        LocalePreferences.FirstDayOfWeek.SUNDAY -> ISO_SUNDAY
        else -> ISO_SUNDAY
    }

private const val ISO_MONDAY = 1
private const val ISO_TUESDAY = 2
private const val ISO_WEDNESDAY = 3
private const val ISO_THURSDAY = 4
private const val ISO_FRIDAY = 5
private const val ISO_SATURDAY = 6
private const val ISO_SUNDAY = 7

/** The week's ISO day numbers starting at the locale's first day, e.g. `[7,1,2,3,4,5,6]` in en-US. */
fun localeWeekDayNumbers(locale: Locale = Locale.getDefault()): List<Int> {
    val first = localeFirstDayOfWeek(locale)
    return List(DAYS_IN_WEEK) { (first - 1 + it) % DAYS_IN_WEEK + 1 }
}
