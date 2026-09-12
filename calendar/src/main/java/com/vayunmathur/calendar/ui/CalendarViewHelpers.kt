package com.vayunmathur.calendar.ui

import androidx.compose.ui.unit.dp
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.library.util.localeFirstDayOfWeek
import java.util.Locale
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlin.time.Instant

internal fun firstDayOfWeekOffset(date: LocalDate, locale: Locale): Int {
    val firstDayOfWeek = localeFirstDayOfWeek(locale)
    return (date.dayOfWeek.isoDayNumber - firstDayOfWeek + 7) % 7
}

/**
 * The key that morphs one event's title into the title on its detail screen, or null when this copy
 * is not the one the morph should start from.
 *
 * Keyed on the instance rather than the event: every occurrence of a repeating event is on screen at
 * once in a month, and they all share an event id. Keyed only on the day the occurrence starts,
 * because an event spanning days is drawn once per day it covers - and the week and month pagers keep
 * the neighbouring pages composed, so those copies are live too. One key, one origin.
 */
internal fun eventTitleMorphKey(instance: Instance, date: LocalDate): String? =
    if (date == instance.startDateTime.date) "calendar-event-title-${instance.id}" else null

internal fun LocalDate.atEndOfDayIn(currentSystemDefault: TimeZone): Instant {
    return this.plus(DatePeriod(days = 1)).atStartOfDayIn(currentSystemDefault)
}

/** Width of the hour-label gutter down the left of the day/week grid. */
internal val HourGutterWidth = 56.dp
