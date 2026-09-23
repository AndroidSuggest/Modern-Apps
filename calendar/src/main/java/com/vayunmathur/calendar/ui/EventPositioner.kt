package com.vayunmathur.calendar.ui

import com.vayunmathur.calendar.data.Instance
import kotlinx.datetime.LocalDate

/**
 * Represents an event slice inside a single day (minutes from 0..1440)
 */
data class PositionedEvent(
    val instanceID: Long,
    val eventID: Long,
    val title: String,
    val color: Int,
    val startMinutes: Int,
    val endMinutes: Int,
    val columnIndex: Int,
    val totalColumns: Int,
    /** How many adjacent columns this event stretches into where neighbours don't overlap it. */
    val columnSpan: Int = 1,
)

fun computePositionedEventsForDay(instances: List<Instance>, day: LocalDate): List<PositionedEvent> {
    // Build per-day slices
    data class Slice(val instanceID: Long, val eventID: Long, val title: String, val color: Int, val start: Int, val end: Int)

    val slices = ArrayList<Slice>()
    for (instance in instances) {
        // determine start and end minutes for this day
        val startMinutes = if (day == instance.spanDays.first()) {
            instance.startDateTime.hour * 60 + instance.startDateTime.minute
        } else 0
        val endMinutes = if (day == instance.spanDays.last()) {
            instance.endDateTime.hour * 60 + instance.endDateTime.minute
        } else 24 * 60

        val s = startMinutes.coerceAtLeast(0).coerceAtMost(24 * 60)
        val e0 = endMinutes.coerceAtLeast(0).coerceAtMost(24 * 60)
        if (e0 < s) continue
        // A zero-duration event is a point in time, not an absence: give it a visible
        // slice instead of dropping it.
        val e = if (e0 == s) (s + 30).coerceAtMost(24 * 60) else e0
        if (s >= e) continue
        slices.add(Slice(instance.id, instance.eventID, instance.eventTitle, instance.color, s, e))
    }

    if (slices.isEmpty()) return emptyList()

    // sort by start then end
    val sorted = slices.withIndex().sortedWith(compareBy({ it.value.start }, { it.value.end }))

    // Build ranges with original index
    data class Range(val idx: Int, val start: Int, val end: Int, val slice: Slice)
    val ranges = sorted.mapIndexed { i, si -> Range(i, si.value.start, si.value.end, si.value) }

    // Split into components (connected overlapping groups)
    val components = ArrayList<List<Range>>()
    var currentComp = ArrayList<Range>()
    var currentEnd = -1
    for (r in ranges) {
        if (currentComp.isEmpty()) {
            currentComp.add(r)
            currentEnd = r.end
        } else {
            if (r.start < currentEnd) {
                currentComp.add(r)
                if (r.end > currentEnd) currentEnd = r.end
            } else {
                components.add(currentComp)
                currentComp = ArrayList()
                currentComp.add(r)
                currentEnd = r.end
            }
        }
    }
    if (currentComp.isNotEmpty()) components.add(currentComp)

    // Prepare output array matching slices order (we'll collect in order of processing)
    val output = ArrayList<PositionedEvent>(slices.size)

    // Process each component with greedy interval partitioning
    for (comp in components) {
        // We need minimal columns for this component
        // pq holds pairs of (endTime, columnIndex)
        val pq = java.util.PriorityQueue<Pair<Int, Int>>(compareBy { it.first })
        val freeCols = ArrayDeque<Int>()
        var nextCol = 0

        // map each range to its column, then widen events rightward into columns that
        // are free for their whole span (uniform 1/peak widths over-narrow events that
        // only overlap part of a busy period).
        val assigned = ArrayList<Pair<Range, Int>>()

        for (r in comp.sortedWith(compareBy({ it.start }, { it.end }))) {
            while (pq.isNotEmpty() && pq.peek()!!.first <= r.start) {
                val freed = pq.poll()!!
                freeCols.addLast(freed.second)
            }
            val col = if (freeCols.isNotEmpty()) freeCols.removeLast() else nextCol++
            pq.add(Pair(r.end, col))
            assigned.add(r to col)
        }

        fun overlaps(a: Range, b: Range): Boolean = a.start < b.end && b.start < a.end

        val used = nextCol
        // set totalColumns for assigned events in this component
        for ((range, col) in assigned) {
            var span = 1
            var c = col + 1
            while (c < used) {
                val blocked = assigned.any { (other, otherCol) ->
                    otherCol == c && overlaps(other, range)
                }
                if (blocked) break
                span++
                c++
            }
            val slice = range.slice
            output.add(
                PositionedEvent(
                    instanceID = slice.instanceID,
                    eventID = slice.eventID,
                    title = slice.title,
                    color = slice.color,
                    startMinutes = range.start,
                    endMinutes = range.end,
                    columnIndex = col,
                    totalColumns = used,
                    columnSpan = span,
                )
            )
        }
    }

    // The output is grouped by components; that's fine for rendering — client doesn't rely on original order
    return output
}
