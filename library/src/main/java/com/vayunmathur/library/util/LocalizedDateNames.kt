package com.vayunmathur.library.util

import android.icu.text.DateFormatSymbols
import android.icu.util.Calendar
import android.icu.util.GregorianCalendar
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
}

// FORMAT rather than STANDALONE: that is the context java.time's plain TextStyle.SHORT /
// TextStyle.FULL resolved to, so inflected locales keep the wording they had before.
private const val CONTEXT = DateFormatSymbols.FORMAT

// Pin the calendar to Gregorian. DateFormatSymbols.getInstance(locale) would follow the
// locale's *preferred* calendar (islamic-umalqura for ar-SA, persian for fa-IR, ...) and hand
// back that calendar's month names, which would then be used to label Gregorian dates.
private fun symbols(locale: Locale) = DateFormatSymbols(GregorianCalendar::class.java, locale)

/** Localized month names, January..December (index 0 == January). */
fun localizedMonthNames(
    style: DateNameStyle,
    locale: Locale = Locale.getDefault(),
): List<String> =
    symbols(locale).getMonths(CONTEXT, style.icuWidth).take(12)

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
    return listOf(
        Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
        Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY,
    ).map { weekdays[it] }
}
