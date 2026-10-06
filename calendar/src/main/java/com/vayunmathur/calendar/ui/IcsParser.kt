package com.vayunmathur.calendar.ui

import android.util.Log
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.util.RRule
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.io.BufferedInputStream
import java.io.InputStream
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

// Non-UI ICS parsing implementation. Kept separate from IcsImport.kt so the UI file
// stays under the FileLength limit. No composables in this file.
internal fun parseICSFileImpl(iS: InputStream): List<Event> {
    val events = mutableListOf<Event>()
    val lines = unfoldIcsLines(BufferedInputStream(iS).bufferedReader().readLines())

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
            handleAlarmLine(line)?.let { alarmReminders.add(it) }
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
            if (!buildEventFromProps(current, rdateProps, exdateProps, alarmReminders, events)) {
                skippedCount++
            }
            inEvent = false
            current = mutableMapOf()
            rdateProps = mutableListOf()
            exdateProps = mutableListOf()
            alarmReminders = mutableListOf()
            continue
        }

        if (!inEvent) continue
        handlePropertyLine(line, current, rdateProps, exdateProps)
    }

    return events.also {
        if (skippedCount > 0) Log.w("IcsImport", "Skipped $skippedCount VEVENT(s)")
    }
}

/**
 * Unfolds ICS lines: a line starting with a space or tab continues the previous
 * one, with exactly one fold-marker character removed.
 */
private fun unfoldIcsLines(rawLines: List<String>): List<String> {
    val lines = mutableListOf<String>()
    for (line in rawLines) {
        if (line.startsWith(" ") || line.startsWith('\t')) {
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
    return lines
}

/**
 * Reads one VALARM content line, returning the minutes-before-start when it is a
 * start-relative TRIGGER (RELATED=END counts from event end and is skipped).
 */
private fun handleAlarmLine(line: String): Int? {
    // Only TRIGGER feeds Event.reminders; RELATED=END counts from event end.
    val alarmColon = line.indexOf(':')
    if (alarmColon <= 0) return null
    val alarmLeft = line.take(alarmColon)
    if (!alarmLeft.substringBefore(';').equals("TRIGGER", ignoreCase = true) ||
        alarmLeft.uppercase().contains("RELATED=END")
    ) {
        return null
    }
    return parseTriggerMinutesBefore(line.substring(alarmColon + 1))
}

/** Routes one VEVENT property line into the current property map or date lists. */
private fun handlePropertyLine(
    line: String,
    current: MutableMap<String, String>,
    rdateProps: MutableList<Pair<String, String>>,
    exdateProps: MutableList<Pair<String, String>>,
) {
    val colonIndex = line.indexOf(':')
    if (colonIndex <= 0) return
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

/**
 * Builds one [Event] from accumulated VEVENT properties, appending it to [events].
 * Returns false when DTSTART was unparseable and the event was skipped.
 */
private fun buildEventFromProps(
    current: Map<String, String>,
    rdateProps: List<Pair<String, String>>,
    exdateProps: List<Pair<String, String>>,
    alarmReminders: List<Int>,
    events: MutableList<Event>,
): Boolean {
    var added = false
    try {
        val uid = current["UID"] ?: current["ID"] ?: ""
        val id = if (uid.isNotBlank()) uid.hashCode().toLong() else null
        val title = unescapeIcsText(current["SUMMARY"] ?: "Untitled")
        val description = unescapeIcsText(current["DESCRIPTION"] ?: "")
        val location = unescapeIcsText(current["LOCATION"] ?: "")

        val (startMillis, startAllDay, startTz) = parseICSTime(current["DTSTART_PROP"], current["DTSTART"])
        val (endMillisRaw, _, endTzRaw) = parseICSTime(current["DTEND_PROP"], current["DTEND"])

        if (startMillis == null) {
            Log.w("IcsImport", "Skipping VEVENT with unparseable DTSTART: $current")
        } else {
            var endMillis = endMillisRaw ?: startMillis
            val duration = current["DURATION"]
            if (endMillisRaw == null && duration != null) {
                endMillis = tryParseDurationMillis(duration, startMillis) ?: startMillis
            }
            val timezone = startTz ?: endTzRaw ?: "UTC"
            val rrule = current["RRULE"]?.let { RRule.parse(it, TimeZone.of(timezone)) }
            if (startAllDay && endMillis == startMillis) {
                endMillis = startMillis + 1.days.inWholeMilliseconds
            }
            val zone = TimeZone.of(timezone)
            val startDate = Instant.fromEpochMilliseconds(startMillis).toLocalDateTime(zone).date
            // DTSTART is an occurrence in its own right; keep RDATE as extra days only.
            val rdate = parseIcsDates(rdateProps, zone).filter { it != startDate }
            val exdate = parseIcsDates(exdateProps, zone)

            val evt = Event(id, -1, title, description, location, null, startMillis, endMillis, timezone,
                startAllDay, rrule, exdate = exdate, rdate = rdate,
                reminders = alarmReminders.distinct().sorted())
            events.add(evt)
            added = true
        }
    } catch (expected: Exception) {
        Log.e("IcsImport", "Error parsing VEVENT", expected)
    }
    return added
}
