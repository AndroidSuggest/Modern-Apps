package com.vayunmathur.library.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import kotlin.time.Instant
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Date and time formatting.
 *
 * Seven apps were on `SimpleDateFormat`, two on `DateTimeFormatter`, and eight
 * on the shared `localizedMonthNames`. `SimpleDateFormat` is the one to get
 * away from: it is mutable and not thread-safe, so a shared instance corrupts
 * its own output under concurrency, and it does not follow the user's
 * 12/24-hour system setting the way [DateFormat.is24HourFormat] does.
 *
 * These follow the locale for date order and the system setting for the clock,
 * which is what users expect and what neither of the other two approaches did
 * consistently.
 */
object DateFormats {

    // Via epoch millis rather than a kotlinx-datetime interop conversion:
    // the apps mix kotlin.time.Instant and java.time.Instant, and this needs
    // neither to be on the classpath of the caller.
    private fun zoned(instant: Instant): ZonedDateTime =
        java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds())
            .atZone(ZoneId.systemDefault())

    /** Date only, in the locale's short form - 3/14/25, 14/03/2025. */
    fun date(instant: Instant, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
            .withLocale(locale)
            .format(zoned(instant))

    /** Date in a longer, readable form - 14 March 2025. */
    fun dateLong(instant: Instant, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
            .withLocale(locale)
            .format(zoned(instant))

    /** Time of day, honouring the system 12/24-hour setting. */
    fun time(
        context: Context,
        instant: Instant,
        locale: Locale = Locale.getDefault(),
    ): String {
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        return DateTimeFormatter.ofPattern(pattern, locale).format(zoned(instant))
    }

    /** Date and time together. */
    fun dateTime(
        context: Context,
        instant: Instant,
        locale: Locale = Locale.getDefault(),
    ): String = "${date(instant, locale)} ${time(context, instant, locale)}"
}

/** [DateFormats.time] using the ambient context and locale. */
@Composable
fun rememberTimeText(instant: Instant): String {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val locale = LocalConfiguration.current.locales[0]
    return DateFormats.time(context, instant, locale)
}
