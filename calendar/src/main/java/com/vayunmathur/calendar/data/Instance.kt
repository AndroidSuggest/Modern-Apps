package com.vayunmathur.calendar.data
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import androidx.core.database.getStringOrNull
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import com.vayunmathur.calendar.util.RRule
import kotlinx.serialization.Serializable
import kotlin.time.Instant


@Serializable
data class Instance(
    val id: Long,
    val eventID: Long,
    val begin: Long,
    val end: Long,
    val timezone: String,
    val allDay: Boolean,
    val eventTitle: String,
    val color: Int,
    val rrule: RRule?,
    val exdate: List<LocalDate> = emptyList()
) {

    val startDateTimeDisplay: LocalDateTime
        get() = Instant.fromEpochMilliseconds(begin).toLocalDateTime(TimeZone.of(timezone))

    val endDateTimeDisplay: LocalDateTime
        get() = Instant.fromEpochMilliseconds(end).toLocalDateTime(TimeZone.of(timezone))

    val startDateTime: LocalDateTime
        get() = Instant.fromEpochMilliseconds(begin).toLocalDateTime(if(allDay) TimeZone.UTC else TimeZone.currentSystemDefault())

    val endDateTime: LocalDateTime
        get() = Instant.fromEpochMilliseconds(end).toLocalDateTime(if(allDay) TimeZone.UTC else TimeZone.currentSystemDefault())


    val spanDays: List<LocalDate>
        get() {
            val startDate = startDateTime.date
            val endDate = if (endDateTime.time == LocalTime(0, 0)) (endDateTime.date - DatePeriod(days = 1)) else endDateTime.date
            return (startDate..endDate).toList()
        }


    companion object {
        /**
         * Instances between [startTime] and [endTime] whose event still exists and
         * whose calendar is visible. [getInstances] reads the provider directly,
         * which can still return rows for just-deleted events (sync-adapter delete
         * semantics / sync lag) with no visibility info — the widget must apply
         * the same live-event + visibility filtering as the in-app views or stale
         * events linger until their date passes.
         */
        fun getVisibleInstances(context: Context, startTime: Instant, endTime: Instant): List<Instance> {
            val instances = getInstances(context, startTime, endTime)
            if (instances.isEmpty()) return emptyList()
            val eventsById = Event.getAllEvents(context).associateBy { it.id }
            val visibility = Calendar.getAllCalendars(context).associate { it.id to it.visible }
            return instances.filter { instance ->
                val event = eventsById[instance.eventID] ?: return@filter false
                visibility[event.calendarID] ?: true
            }
        }

        fun getInstances(context: Context, startTime: Instant, endTime: Instant): List<Instance> {
            val instances = mutableListOf<Instance>()

            val projection = arrayOf(
                CalendarContract.Instances._ID,
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_TIMEZONE,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.DISPLAY_COLOR,
                CalendarContract.Instances.RRULE
            )
            try {
                val cursor = CalendarContract.Instances.query(
                    context.contentResolver,
                    projection,
                    startTime.toEpochMilliseconds(),
                    endTime.toEpochMilliseconds()
                )
                cursor?.use {
                    val idIdx = it.getColumnIndexOrThrow(CalendarContract.Instances._ID)
                    val eventIdIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
                    val beginIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                    val endIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.END)
                    val tzIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_TIMEZONE)
                    val allDayIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                    val titleIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                    val colorIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.DISPLAY_COLOR)
                    val rruleIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.RRULE)

                    while (it.moveToNext()) {
                        try {
                            val id = it.getLong(idIdx)
                            val eventID = it.getLong(eventIdIdx)
                            val start = it.getLong(beginIdx)
                            val end = it.getLong(endIdx)
                            val tz = runCatching {
                                TimeZone.of(it.getStringOrNull(tzIdx) ?: TimeZone.currentSystemDefault().id)
                            }.getOrDefault(TimeZone.currentSystemDefault())
                            val timezone = tz.id
                            val allDay = it.getInt(allDayIdx) > 0
                            val eventTitle = it.getString(titleIdx)
                            val color = it.getInt(colorIdx)
                            val rrule = RRule.parse(it.getStringOrNull(rruleIdx) ?: "", tz)

                            instances.add(Instance(id, eventID, start, end, timezone, allDay, eventTitle, color, rrule))
                        } catch (e: Exception) {
                            Log.e("Instance", "Error constructing instance from cursor", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Instance", "Error querying instances", e)
            }

            return instances
        }
    }
}
