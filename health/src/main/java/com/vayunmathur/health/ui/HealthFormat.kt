package com.vayunmathur.health.ui

import android.content.Context
import com.vayunmathur.health.R
import com.vayunmathur.library.ui.DateString
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toKotlinLocalTime
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes

/** Formats a minute count as "Xh Ym" or "Ym". */
fun hoursMinutesString(context: Context, minutes: Long): String =
    minutes.minutes.toComponents { h, m, _, _ ->
        if (h > 0) context.getString(R.string.hours_minutes_format, h, m)
        else context.getString(R.string.minutes_format, m)
    }

/** The local calendar date an [Instant] falls on. */
fun Instant.toLocalDate(): kotlinx.datetime.LocalDate =
    atZone(ZoneId.systemDefault()).toLocalDate().toKotlinLocalDate()

/**
 * A medical record's date, spelled out in full.
 *
 * The long form rather than the short one because these are read years after the fact, where "3/4"
 * is genuinely ambiguous and the year matters.
 */
fun medicalDateString(instant: Instant): String = DateString.dateLong(instant.toLocalDate())

/**
 * A medical record's date and time of day.
 *
 * For the few places where the hour matters as much as the day — when a dose was taken, when the
 * next one is due. Honours the device's 12- or 24-hour setting through [DateString].
 */
fun medicalDateTimeString(instant: Instant, is24Hour: Boolean): String {
    val local = instant.atZone(ZoneId.systemDefault())
    return DateString.dateTime(
        kotlinx.datetime.LocalDateTime(
            local.toLocalDate().toKotlinLocalDate(),
            local.toLocalTime().toKotlinLocalTime(),
        ),
        is24Hour,
    )
}

/** Joins the parts that are present with a middle dot, the app's inline detail separator. */
fun detailLine(vararg parts: String?): String =
    parts.filter { !it.isNullOrBlank() }.joinToString(" \u00B7 ")

/** Midnight local time on this date, which is the precision a medical record carries. */
fun kotlinx.datetime.LocalDate.toInstant(): Instant =
    Instant.ofEpochMilli(
        atStartOfDayIn(kotlinx.datetime.TimeZone.currentSystemDefault()).toEpochMilliseconds()
    )
