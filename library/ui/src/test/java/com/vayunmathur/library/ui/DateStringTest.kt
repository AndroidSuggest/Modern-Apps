package com.vayunmathur.library.ui

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the [DateString] forms that don't touch `android.icu` / `android.text.format`,
 * so they run on the plain-JVM unit-test classpath (this module has no Robolectric):
 *
 *  - [DateString.dateShort] — locale-ordered date via `java.time`.
 *  - [DateString.timeNumeric] / [DateString.timeSecondsNumeric] — pure kotlinx-datetime.
 *
 * The localized-name forms (weekday/month names, AM/PM markers) are exercised by the
 * app screens and manual/instrumented runs; the ICU stubs make them un-unit-testable here.
 */
class DateStringTest {

    private val pi = LocalDate(2025, 3, 14) // 14 March 2025

    @Test fun dateShortUsesLocaleFieldOrder() {
        assertEquals("3/14/25", DateString.dateShort(pi, Locale.US))
        assertEquals("14.03.25", DateString.dateShort(pi, Locale.GERMANY))
    }

    @Test fun timeNumeric24HourIsZeroPadded() {
        assertEquals("15:05", DateString.timeNumeric(LocalTime(15, 5), is24Hour = true))
        assertEquals("09:05", DateString.timeNumeric(LocalTime(9, 5), is24Hour = true))
        assertEquals("00:00", DateString.timeNumeric(LocalTime(0, 0), is24Hour = true))
    }

    @Test fun timeNumeric12HourDropsLeadingHourZero() {
        assertEquals("3:05", DateString.timeNumeric(LocalTime(15, 5), is24Hour = false))
        assertEquals("9:05", DateString.timeNumeric(LocalTime(9, 5), is24Hour = false))
        // Midnight and noon read as 12 in 12-hour form.
        assertEquals("12:00", DateString.timeNumeric(LocalTime(0, 0), is24Hour = false))
        assertEquals("12:30", DateString.timeNumeric(LocalTime(12, 30), is24Hour = false))
    }

    @Test fun timeSecondsNumericAppendsSeconds() {
        assertEquals("15:05:23", DateString.timeSecondsNumeric(LocalTime(15, 5, 23), is24Hour = true))
        assertEquals("3:05:23", DateString.timeSecondsNumeric(LocalTime(15, 5, 23), is24Hour = false))
    }
}
