package com.vayunmathur.maps.data

import com.vayunmathur.library.util.localizedAmPmMarker
import kotlinx.datetime.format.DateTimeFormat
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.Padding
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

@Serializable
class OpeningHours(val rawString: String) {

    private val rules: List<OpeningRule> = parse(rawString)

    companion object {
        fun from(input: String): OpeningHours = OpeningHours(input)

        private fun parse(input: String): List<OpeningRule> {
            // Wrap each rule parse in runCatching so a single malformed segment
            // doesn't crash the bottom sheet. Real OSM `opening_hours` strings
            // include things like "24/7", "Mo-Fr sunrise-sunset", "PH", or
            // localised abbreviations we don't handle yet — silently drop those
            // rather than throwing NumberFormatException up the UI stack.
            return input.split(";").mapNotNull { part ->
                runCatching {
                    val trimmed = part.trim()
                    if (trimmed.isEmpty()) return@runCatching null

                    val lastSpace = trimmed.lastIndexOf(' ')
                    val dayPart = if (lastSpace != -1) trimmed.substring(0, lastSpace) else "Mo-Su"
                    val timePart = trimmed.substring(lastSpace + 1)

                    OpeningRule(
                        days = parseDays(dayPart),
                        intervals = parseIntervals(timePart)
                    )
                }.getOrNull()
            }
        }

        private fun parseDays(dayStr: String): Set<DayOfWeek> {
            val days = mutableSetOf<DayOfWeek>()
            val map = mapOf(
                "Mo" to DayOfWeek.MONDAY, "Tu" to DayOfWeek.TUESDAY,
                "We" to DayOfWeek.WEDNESDAY, "Th" to DayOfWeek.THURSDAY,
                "Fr" to DayOfWeek.FRIDAY, "Sa" to DayOfWeek.SATURDAY,
                "Su" to DayOfWeek.SUNDAY
            )

            if (dayStr == "Mo-Su" || dayStr.isBlank()) return DayOfWeek.entries.toSet()

            dayStr.split(",").forEach { segment ->
                val cleanSegment = segment.trim()
                if (cleanSegment.contains("-")) {
                    val parts = cleanSegment.split("-")
                    if (parts.size < 2) return@forEach
                    val start = map[parts[0]] ?: return@forEach
                    val end = map[parts[1]] ?: return@forEach
                    var curr = start
                    while (curr != end) {
                        days.add(curr)
                        curr = DayOfWeek.entries[(curr.ordinal + 1) % 7]
                    }
                    days.add(end)
                } else {
                    map[cleanSegment]?.let { days.add(it) }
                }
            }
            return days
        }

        private fun parseIntervals(timeStr: String): List<TimeInterval> {
            if (timeStr == "off") return emptyList()
            return timeStr.split(",").mapNotNull {
                val range = it.split("-")
                if (range.size != 2) return@mapNotNull null
                val start = parseCustomTime(range[0]) ?: return@mapNotNull null
                val end = parseCustomTime(range[1]) ?: return@mapNotNull null
                TimeInterval(start, end)
            }
        }

        // Returns null for unparseable strings instead of throwing — OSM has
        // entries like "sunrise", "12:00+", "dusk" that we can't represent.
        private fun parseCustomTime(time: String): LocalTime? {
            val parts = time.trim().split(":")
            if (parts.isEmpty() || parts[0].isBlank()) return null
            return try {
                var hour = parts[0].toInt()
                val minute = if (parts.size > 1) parts[1].toIntOrNull() ?: 0 else 0
                if (hour >= 24) hour -= 24
                if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null
                LocalTime(hour, minute)
            } catch (_: NumberFormatException) {
                null
            }
        }
    }

    fun isOpen(dateTime: LocalDateTime): Boolean {
        val rule = rules.findLast { it.days.contains(dateTime.dayOfWeek) } ?: return false
        return rule.intervals.any { it.contains(dateTime.time) }
    }

    /**
     * Finds the next status change by checking the boundaries of intervals
     * across the next 7 days.
     */
    fun nextStatusChangeTime(current: LocalDateTime): LocalDateTime {
        val currentlyOpen = isOpen(current)
        val timeZone = TimeZone.currentSystemDefault()

        // Check today and the next 6 days
        for (i in 0..7) {
            val date = current.toInstant(timeZone)
                .plus(i, DateTimeUnit.DAY, timeZone)
                .toLocalDateTime(timeZone)
                .date

            val dayOfWeek = date.dayOfWeek
            val rule = rules.findLast { it.days.contains(dayOfWeek) } ?: continue

            // Collect all relevant times for this day
            val changeTimes = rule.intervals.flatMap { listOf(it.start, it.end) }.distinct().sorted()

            for (time in changeTimes) {
                val candidate = LocalDateTime(date, time)
                if (candidate > current && isOpen(candidate) != currentlyOpen) {
                    return candidate
                }
            }
        }
        return current
    }

    fun openingHours(): Map<DayOfWeek, String> {
        return DayOfWeek.entries.associateWith { day ->
            val rule = rules.findLast { it.days.contains(day) }
            if (rule == null || rule.intervals.isEmpty()) "Closed"
            else rule.intervals.joinToString(", ") { "${it.start.format(timeFormat)}-${it.end.format(timeFormat)}" }
        }
    }
}

val timeFormat: DateTimeFormat<LocalTime> get() = LocalTime.Format {
    amPmHour(Padding.NONE)
    chars(":")
    minute()
    chars(" ")
    localizedAmPmMarker()
}

@Serializable
private data class OpeningRule(val days: Set<DayOfWeek>, val intervals: List<TimeInterval>)

@Serializable
private data class TimeInterval(val start: LocalTime, val end: LocalTime) {
    fun contains(time: LocalTime): Boolean {
        return if (end < start) {
            time !in end..<start
        } else {
            time in start..<end
        }
    }
}