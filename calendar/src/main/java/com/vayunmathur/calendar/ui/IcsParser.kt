package com.vayunmathur.calendar.ui

import android.util.Log
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.util.AllDayFormat
import com.vayunmathur.calendar.util.BasicIsoInstantFormat
import com.vayunmathur.calendar.util.RRule
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.format.char
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.io.BufferedInputStream
import java.io.InputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

// Non-UI ICS parsing implementation. Kept separate from IcsImport.kt so the UI file
// stays under the FileLength limit. No composables in this file.
internal fun parseICSFileImpl(iS: InputStream): List<Event> {
    val events = mutableListOf<Event>()

    // Read and unfold lines (lines that start with space or tab are continuations)
    val rawLines = BufferedInputStream(iS).bufferedReader().readLines()
    val lines = mutableListOf<String>()
    for (line in rawLines) {
        if (line.startsWith(" ") || line.startsWith('\t')) {
            // Exactly one character of the continuation is the fold marker.
            val continued = line.substring(1)
            if (lines.isNotEmpty()) {
                val prev = lines.removeAt(lines.size - 1)
                lines.add(prev + continued)
            } else {
                lines.add(continued)
            }
        } else {
            lines.add(line)
        }
    }

    var current = mutableMapOf<String, String>()
    // RDATE and EXDATE can appear on several lines in one VEVENT, so they accumulate.
    var rdateProps = mutableListOf<Pair<String, String>>()
    var exdateProps = mutableListOf<Pair<String, String>>()
    var inEvent = false
    // VALARM sits inside VEVENT and carries its own DESCRIPTION; only TRIGGER is read.
    var inAlarm = false
    var alarmReminders = mutableListOf<Int>()
    var skippedCount = 0

    for (raw in lines) {
        val line = raw.trimEnd()
        if (line.equals("BEGIN:VALARM", ignoreCase = true)) {
            inAlarm = true
            continue
        }
        if (line.equals("END:VALARM", ignoreCase = true)) {
            inAlarm = false
            continue
        }
        if (inAlarm) {
            // Only TRIGGER feeds Event.reminders; RELATED=END counts from event end.
            val alarmColon = line.indexOf(':')
            if (alarmColon > 0) {
                val alarmLeft = line.take(alarmColon)
                if (alarmLeft.substringBefore(';').equals("TRIGGER", ignoreCase = true) &&
                    !alarmLeft.uppercase().contains("RELATED=END")
                ) {
                    parseTriggerMinutesBefore(line.substring(alarmColon + 1))?.let { alarmReminders.add(it) }
                }
            }
            continue
        }
        if (line.equals("BEGIN:VEVENT", ignoreCase = true)) {
            inEvent = true
            current = mutableMapOf()
            rdateProps = mutableListOf()
            exdateProps = mutableListOf()
            alarmReminders = mutableListOf()
            continue
        }
        if (line.equals("END:VEVENT", ignoreCase = true)) {
            var added = false
            var failed: Exception? = null
            try {
                val uid = current["UID"] ?: current["ID"] ?: ""
                val id = if (uid.isNotBlank()) uid.hashCode().toLong() else null
                val title = unescapeIcsText(current["SUMMARY"] ?: "Untitled")
                val description = unescapeIcsText(current["DESCRIPTION"] ?: "")
                val location = unescapeIcsText(current["LOCATION"] ?: "")

                val (startMillis, startAllDay, startTz) = parseICSTime(current["DTSTART_PROP"], current["DTSTART"])
                val (endMillisRaw, _, endTzRaw) = parseICSTime(current["DTEND_PROP"], current["DTEND"])

                if (startMillis == null) {
                    skippedCount++
                    Log.w("IcsImport", "Skipping VEVENT with unparseable DTSTART: $current")
                } else {
                    var endMillis = endMillisRaw
                    if (endMillis == null) {
                        val duration = current["DURATION"]
                        if (duration != null) {
                            endMillis = tryParseDurationMillis(duration, startMillis)
                        }
                    }
                    endMillis = endMillis ?: startMillis
                    val timezone = startTz ?: endTzRaw ?: "UTC"
                    val rrule = current["RRULE"]?.let { RRule.parse(it, TimeZone.of(timezone)) }
                    if (startAllDay && endMillis == startMillis) {
                        endMillis = startMillis + 1.days.inWholeMilliseconds
                    }
                    val zone = TimeZone.of(timezone)
                    val startDate =
                        Instant.fromEpochMilliseconds(startMillis).toLocalDateTime(zone).date
                    // DTSTART is an occurrence in its own right; keep RDATE as extra days only.
                    val rdate = parseIcsDates(rdateProps, zone).filter { it != startDate }
                    val exdate = parseIcsDates(exdateProps, zone)

                    val evt = Event(id, -1, title, description, location, null, startMillis, endMillis, timezone,
                        startAllDay, rrule, exdate = exdate, rdate = rdate,
                        reminders = alarmReminders.distinct().sorted())
                    events.add(evt)
                    added = true
                }
            } catch (e: Exception) {
                failed = e
                Log.e("IcsImport", "Error parsing VEVENT", e)
            }
            if (!added && failed != null) skippedCount++
            inEvent = false
            current = mutableMapOf()
            rdateProps = mutableListOf()
            exdateProps = mutableListOf()
            alarmReminders = mutableListOf()
            continue
        }

        if (!inEvent) continue

        val colonIndex = line.indexOf(':')
        if (colonIndex <= 0) continue
        val left = line.take(colonIndex)
        val value = line.substring(colonIndex + 1)

        val semicolonIndex = left.indexOf(';')
        val propName = if (semicolonIndex > 0) left.take(semicolonIndex).uppercase() else left.uppercase()

        when (propName) {
            "DTSTART" -> {
                current["DTSTART"] = value
                current["DTSTART_PROP"] = left
            }
            "DTEND" -> {
                current["DTEND"] = value
                current["DTEND_PROP"] = left
            }
            "RDATE" -> rdateProps.add(left to value)
            "EXDATE" -> exdateProps.add(left to value)
            else -> current[propName] = value
        }
    }

    return events.also {
        if (skippedCount > 0) Log.w("IcsImport", "Skipped $skippedCount VEVENT(s) with unparseable DTSTART")
    }
}

/** Reverses RFC 5545 TEXT escaping. */
internal fun unescapeIcsText(value: String): String {
    if ('\\' !in value) return value
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '\\' && i + 1 < value.length) {
            when (val next = value[i + 1]) {
                'n', 'N' -> out.append('\n')
                else -> out.append(next)
            }
            i += 2
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

/** The days a set of RDATE or EXDATE lines names, in [zone]. */
internal fun parseIcsDates(props: List<Pair<String, String>>, zone: TimeZone): List<LocalDate> =
    props.flatMap { (left, values) -> values.split(",").map { left to it.trim() } }
        .mapNotNull { (left, value) ->
            if (value.length == 8 && value.all { it.isDigit() }) {
                runCatching { AllDayFormat.parse(value) }.getOrNull()
            } else {
                parseICSTime(left, value).first?.let {
                    Instant.fromEpochMilliseconds(it).toLocalDateTime(zone).date
                }
            }
        }
        .distinct()
        .sorted()

// Parse ICS time value with optional params-left (like DTSTART;TZID=America/Los_Angeles)
internal fun parseICSTime(propLeft: String?, value: String?): Triple<Long?, Boolean, String?> {
    if (value == null) return Triple(null, false, null)

    val left = propLeft ?: ""
    val up = left.uppercase()

    val isAllDay = up.contains("VALUE=DATE") || value.length == 8 && value.all { it.isDigit() }

    return try {
        if (isAllDay) {
            val dt = AllDayFormat.parse(value)
            val start = dt.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
            Triple(start, true, "UTC")
        } else {
            if (value.endsWith("Z")) {
                val instant = runCatching { BasicIsoInstantFormat.parse(value).toInstantUsingOffset() }
                    .getOrElse { Instant.parse(value) }
                Triple(instant.toEpochMilliseconds(), false, "UTC")
            } else if (hasExplicitOffset(value)) {
                val instant = runCatching { BasicIsoInstantFormat.parse(value).toInstantUsingOffset() }
                    .getOrElse { Instant.parse(value) }
                Triple(instant.toEpochMilliseconds(), false, "UTC")
            } else {
                val tzid = extractTZID(left)
                val candidates = listOf(DateTimeFormat, DateTimeShortFormat, DashedDateTimeFormat, DashedDateTimeShortFormat)
                var parsedInstant: Instant? = null

                for (fmt in candidates) {
                    try {
                        val ldt = LocalDateTime.parse(value, fmt)
                        val zone = tzid?.let { TimeZone.of(it) } ?: TimeZone.currentSystemDefault()
                        parsedInstant = ldt.toInstant(zone)
                        break
                    } catch (_: IllegalArgumentException) {
                        // try next candidate
                    }
                }

                if (parsedInstant != null) {
                    Triple(parsedInstant.toEpochMilliseconds(), false, tzid ?: TimeZone.currentSystemDefault().id)
                } else {
                    Triple(null, false, tzid)
                }
            }
        }
    } catch (e: Exception) {
        Log.e("IcsImport", "Error parsing ICS time: $value", e)
        Triple(null, false, null)
    }
}

internal fun extractTZID(left: String): String? =
    left.split(';')
        .map { it.split('=', limit = 2) }
        .firstOrNull { it.size == 2 && it[0].uppercase() == "TZID" }
        ?.get(1)

internal fun tryParseDurationMillis(duration: String, startMillis: Long): Long? =
    runCatching { startMillis + Duration.parse(duration).inWholeMilliseconds }.getOrNull()

// Formats for local times without offset
val DateTimeFormat = LocalDateTime.Format {
    year(); monthNumber(); day()
    char('T')
    hour(); minute(); second()
}

val DateTimeShortFormat = LocalDateTime.Format {
    year(); monthNumber(); day()
    char('T')
    hour(); minute()
}

// Dashed ISO-8601 floating forms (e.g. 2025-01-01T09:00:00).
val DashedDateTimeFormat = LocalDateTime.Format {
    year(); char('-'); monthNumber(); char('-'); day()
    char('T')
    hour(); char(':'); minute(); char(':'); second()
}

val DashedDateTimeShortFormat = LocalDateTime.Format {
    year(); char('-'); monthNumber(); char('-'); day()
    char('T')
    hour(); char(':'); minute()
}

/** True when [value] carries an explicit numeric UTC offset (not a date dash). */
internal fun hasExplicitOffset(value: String): Boolean {
    if ('+' in value) return true
    val tIndex = value.indexOf('T')
    if (tIndex < 0) return false
    return '-' in value.substring(tIndex + 1)
}

/**
 * Parses a VALARM TRIGGER value to minutes-before-start, or null when it cannot be expressed
 * that way. Handles negative durations, zero (at-start = 0), and duration-with-absolute-time
 * forms (keeps only the duration half before ';'). Absolute datetimes and positive
 * (post-start) durations return null.
 */
internal fun parseTriggerMinutesBefore(raw: String): Int? {
    val durationPart = raw.substringBefore(';').trim()
    if (durationPart.isEmpty()) return null
    val s = durationPart.uppercase()
    if (!s.startsWith("P") && !s.startsWith("-P")) return null
    val duration = runCatching { Duration.parse(durationPart) }.getOrNull() ?: return null
    if (duration.isPositive()) return null
    val minutes = duration.absoluteValue.inWholeMinutes
    return if (minutes > Int.MAX_VALUE) Int.MAX_VALUE else minutes.toInt()
}
