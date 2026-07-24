package com.vayunmathur.library.util

import java.time.DayOfWeek
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * Locale-aware date-name helpers.
 *
 * kotlinx-datetime's `MonthNames.ENGLISH_*` / `DayOfWeekNames.ENGLISH_*` constants
 * hard-code English names regardless of the device locale. These helpers return the
 * localized names for the current (or given) locale so callers can build
 * `MonthNames(localizedMonthNames(...))` / `DayOfWeekNames(localizedDayOfWeekNames(...))`.
 */

/** Localized month names, January..December (index 0 == January). */
fun localizedMonthNames(
    style: TextStyle,
    locale: Locale = Locale.getDefault(),
): List<String> = (1..12).map { Month.of(it).getDisplayName(style, locale) }

/** Localized day-of-week names, Monday..Sunday (ISO order, matching kotlinx-datetime). */
fun localizedDayOfWeekNames(
    style: TextStyle,
    locale: Locale = Locale.getDefault(),
): List<String> = DayOfWeek.entries.map { it.getDisplayName(style, locale) }
