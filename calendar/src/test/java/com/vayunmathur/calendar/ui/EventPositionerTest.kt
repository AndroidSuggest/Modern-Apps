package com.vayunmathur.calendar.ui

import com.vayunmathur.calendar.data.Instance
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Test

class EventPositionerTest {
    private fun millis(date: LocalDate, minutes: Int): Long =
        date.atTime(minutes / 60, minutes % 60).toInstant(TimeZone.UTC).toEpochMilliseconds()

    private fun timedInstance(atMinutes: Int): Instance {
        val day = LocalDate(2026, 9, 22)
        val at = millis(day, atMinutes)
        return Instance(
            id = 1L,
            eventID = 2L,
            begin = at,
            end = at,
            timezone = "UTC",
            allDay = false,
            eventTitle = "Standup",
            color = 0,
            rrule = null,
        )
    }

    // Regression test for #736: a newly added entry defaults to start == end
    // (EditEventScreen seeds both from now when no beginTime is given). The day
    // grid must render it as a point in time, not drop it as an absence.
    @Test
    fun `zero-duration event gets a visible slice instead of being dropped`() {
        val instance = timedInstance(9 * 60 + 30)
        val day = instance.spanDays.single()
        val positioned = computePositionedEventsForDay(listOf(instance), day)
        assertEquals(1, positioned.size)
        assertEquals(30, positioned[0].endMinutes - positioned[0].startMinutes)
    }
}
