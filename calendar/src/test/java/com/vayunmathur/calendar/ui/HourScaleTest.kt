package com.vayunmathur.calendar.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class HourScaleTest {
    private fun event(startMinutes: Int, endMinutes: Int) = PositionedEvent(
        instanceID = 1,
        eventID = 1,
        title = "",
        color = 0,
        startMinutes = startMinutes,
        endMinutes = endMinutes,
        columnIndex = 0,
        totalColumns = 1,
    )

    @Test
    fun `busy hours cover every hour an event spans`() {
        assertEquals(setOf(9, 10, 11), busyHours(listOf(event(9 * 60 + 30, 11 * 60 + 30))))
    }

    @Test
    fun `an event ending on the hour does not claim that hour`() {
        assertEquals(setOf(9), busyHours(listOf(event(9 * 60, 10 * 60))))
    }

    @Test
    fun `uniform scale places a time at its proportional offset`() {
        val scale = HourScale.uniform()
        assertEquals(56.dp * 9.5f, scale.offsetOf(9 * 60 + 30))
        assertEquals(56.dp * 24f, scale.offsetOf(24 * 60))
    }

    @Test
    fun `collapsed hours compress the offsets before them`() {
        // Only 09:00 is busy, so hours 0..8 are collapsed to 18dp each.
        val scale = HourScale.collapsingEmptyHours(setOf(9), available = 1000.dp)
        assertEquals(18.dp * 9f, scale.offsetOf(9 * 60))
        assertEquals(18.dp * 9f + scale.heightOf(9) / 2f, scale.offsetOf(9 * 60 + 30))
    }

    @Test
    fun `a full hour never grows past its natural height`() {
        val scale = HourScale.collapsingEmptyHours(setOf(9), available = 10_000.dp)
        assertEquals(56.dp, scale.heightOf(9))
    }

    @Test
    fun `full hours shrink to fit the available height`() {
        val busy = (0..11).toSet()
        // 12 collapsed hours take 216dp, leaving 480dp to split across the 12 busy ones.
        val scale = HourScale.collapsingEmptyHours(busy, available = 696.dp)
        assertEquals(40.dp, scale.heightOf(0))
        assertEquals(696.dp, scale.offsetOf(24 * 60))
    }

    @Test
    fun `full hours stop shrinking once they would be unreadable`() {
        val scale = HourScale.collapsingEmptyHours((0..11).toSet(), available = 300.dp)
        assertEquals(28.dp, scale.heightOf(0))
    }
}
