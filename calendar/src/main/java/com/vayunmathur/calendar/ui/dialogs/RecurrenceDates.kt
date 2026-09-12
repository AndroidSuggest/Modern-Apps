package com.vayunmathur.calendar.ui.dialogs

import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber

fun dayOfYear(date: LocalDate): Int =
    (date.toEpochDays() - LocalDate(date.year, 1, 1).toEpochDays() + 1).toInt()

fun isoWeeksInYear(year: Int): Int {
    val jan1 = LocalDate(year, 1, 1).dayOfWeek.isoDayNumber
    val leap = (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
    return if (jan1 == 4 || (leap && jan1 == 3)) 53 else 52
}

fun isoWeekNumber(date: LocalDate): Int {
    val week = (dayOfYear(date) - date.dayOfWeek.isoDayNumber + 10) / 7
    return when {
        week < 1 -> isoWeeksInYear(date.year - 1)
        week > isoWeeksInYear(date.year) -> 1
        else -> week
    }
}

fun ordinal(int: Int): String {
    return int.toString() + (when (int % 100) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        in 4..20 -> "th"
        else -> null
    } ?: when (int % 10) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        else -> "th"
    })
}
