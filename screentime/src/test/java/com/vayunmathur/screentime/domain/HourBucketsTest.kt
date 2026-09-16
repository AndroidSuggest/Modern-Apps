package com.vayunmathur.screentime.domain

import com.vayunmathur.screentime.domain.HourBuckets.HOURS_PER_DAY
import com.vayunmathur.screentime.domain.HourBuckets.MILLIS_PER_HOUR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HourBucketsTest {

    private val dayStart = 1_000_000_000_000L
    private val dayEnd = dayStart + HOURS_PER_DAY * MILLIS_PER_HOUR
    private fun h(hour: Int): Long = dayStart + hour * MILLIS_PER_HOUR

    @Test
    fun intervalInsideOneHourStaysInThatBar() {
        val hours = LongArray(HOURS_PER_DAY)
        HourBuckets.addInterval(hours, dayStart, dayEnd, h(9) + 1_000L, h(9) + 2_000L)
        assertEquals(1_000L, hours[9])
        assertEquals(1_000L, hours.sum())
    }

    @Test
    fun intervalSplitAcrossHourBoundary() {
        val hours = LongArray(HOURS_PER_DAY)
        HourBuckets.addInterval(hours, dayStart, dayEnd, h(1) + MILLIS_PER_HOUR / 2, h(3) + MILLIS_PER_HOUR / 4)
        assertEquals(MILLIS_PER_HOUR / 2, hours[1])
        assertEquals(MILLIS_PER_HOUR, hours[2])
        assertEquals(MILLIS_PER_HOUR / 4, hours[3])
        assertEquals(0L, hours[0])
        assertEquals(0L, hours[4])
    }

    @Test
    fun intervalClippedToDayStart() {
        val hours = LongArray(HOURS_PER_DAY)
        HourBuckets.addInterval(hours, dayStart, dayEnd, dayStart - 5 * MILLIS_PER_HOUR, h(1))
        assertEquals(MILLIS_PER_HOUR, hours[0])
        assertEquals(0L, hours[1])
    }

    @Test
    fun intervalClippedToDayEndSoFutureStaysZero() {
        val hours = LongArray(HOURS_PER_DAY)
        // 11 AM usage running past the 11 AM query edge: only the elapsed part counts.
        HourBuckets.addInterval(hours, dayStart, h(11), h(10) + MILLIS_PER_HOUR / 2, h(12))
        assertEquals(MILLIS_PER_HOUR / 2, hours[10])
        assertEquals(0L, hours[11])
    }

    @Test
    fun previousDayUsageNeverLandsInTodaysBars() {
        val hours = LongArray(HOURS_PER_DAY)
        // Yesterday 4 PM-5 PM: the clock-hour twin (today 4 PM) must stay zero.
        val yesterday16 = dayStart - 8 * MILLIS_PER_HOUR
        HourBuckets.addInterval(hours, dayStart, dayEnd, yesterday16, yesterday16 + MILLIS_PER_HOUR)
        assertTrue(hours.all { it == 0L })
        assertEquals(0L, hours[16])
    }

    @Test
    fun nextDayUsageNeverLandsInTodaysBars() {
        val hours = LongArray(HOURS_PER_DAY)
        HourBuckets.addInterval(hours, dayStart, dayEnd, dayEnd + MILLIS_PER_HOUR, dayEnd + 2 * MILLIS_PER_HOUR)
        assertTrue(hours.all { it == 0L })
    }

    @Test
    fun emptyAndInvertedIntervalsIgnored() {
        val hours = LongArray(HOURS_PER_DAY)
        HourBuckets.addInterval(hours, dayStart, dayEnd, h(5), h(5))
        HourBuckets.addInterval(hours, dayStart, dayEnd, h(6), h(5))
        assertTrue(hours.all { it == 0L })
    }

    @Test
    fun shortDstDayClipsToRealMidnight() {
        val hours = LongArray(HOURS_PER_DAY)
        val shortEnd = dayStart + 23 * MILLIS_PER_HOUR
        HourBuckets.addInterval(hours, dayStart, shortEnd, dayStart + 22 * MILLIS_PER_HOUR + MILLIS_PER_HOUR / 2, dayStart + 24 * MILLIS_PER_HOUR)
        assertEquals(MILLIS_PER_HOUR / 2, hours[22])
        assertEquals(0L, hours[23])
    }
}
