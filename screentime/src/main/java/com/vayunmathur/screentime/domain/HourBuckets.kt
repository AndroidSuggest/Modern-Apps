package com.vayunmathur.screentime.domain

/**
 * Hourly bucketing for the Screen Time day chart.
 *
 * Pure logic (no Android): the chart's 24 bars each cover one hour since local midnight.
 * Intervals are clipped to [dayStart, dayEnd) so usage from any other day can never land
 * in this day's bars - including its clock-hour twin (yesterday 4 PM must not appear as
 * today 4 PM) - and future hours stay zero until their time actually arrives.
 */
object HourBuckets {

    const val HOURS_PER_DAY = 24
    const val MILLIS_PER_HOUR = 3_600_000L

    /**
     * Adds the foreground interval [from, to) to [hours], clipping to [dayStart, dayEnd).
     *
     * Fully out-of-window intervals contribute nothing; empty or inverted intervals are
     * ignored. [dayEnd] (not `dayStart + 24h`) bounds the day so DST transitions with a
     * 23/25-hour day still bucket correctly.
     */
    fun addInterval(hours: LongArray, dayStart: Long, dayEnd: Long, from: Long, to: Long) {
        require(hours.size == HOURS_PER_DAY) { "day chart needs exactly 24 hour buckets" }
        var cursor = from.coerceAtLeast(dayStart)
        val stop = to.coerceAtMost(dayEnd)
        while (cursor < stop) {
            val hourIndex = ((cursor - dayStart) / MILLIS_PER_HOUR).toInt().coerceIn(0, HOURS_PER_DAY - 1)
            // The last bar absorbs any DST overflow past 24h so nothing is silently dropped.
            val hourEnd = if (hourIndex == HOURS_PER_DAY - 1) {
                dayEnd
            } else {
                minOf(dayStart + (hourIndex + 1) * MILLIS_PER_HOUR, dayEnd)
            }
            val slice = minOf(stop, hourEnd) - cursor
            if (slice <= 0L) break
            hours[hourIndex] += slice
            cursor += slice
        }
    }
}
