package com.vayunmathur.weather.domain

import com.vayunmathur.weather.network.Daily
import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.network.Hourly
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Identity of "today" and "now" within a [ForecastResponse].
 *
 * The forecast endpoint returns `past_days` of history prepended to the
 * hourly/daily arrays, so today is no longer at index 0. Identity is derived
 * from `current.time` (already in the location's zone) by date/hour prefix
 * match, with a [ForecastResponse.utcOffsetSeconds]-based fallback when
 * `current` is missing or malformed.
 */

/** Today's date as an ISO `yyyy-MM-dd` string in the location's zone. */
fun todayIsoDate(forecast: ForecastResponse): String {
    val fromCurrent = forecast.current?.time?.substringBefore('T')?.takeIf { it.length == 10 }
    if (fromCurrent != null) return fromCurrent
    val now = System.currentTimeMillis() / 1000
    return kotlin.time.Instant.fromEpochSeconds(now + forecast.utcOffsetSeconds)
        .toLocalDateTime(TimeZone.UTC).date.toString()
}

/** Index of today within [Daily.time], or 0 when absent. */
fun todayIndex(daily: Daily?, currentTime: String?): Int {
    if (daily == null || daily.time.isEmpty()) return 0
    val today = currentTime?.substringBefore('T')?.takeIf { it.length == 10 }
        ?: return daily.time.size.coerceAtMost(8).dec().coerceAtLeast(0)
    return daily.time.indexOf(today).takeIf { it >= 0 } ?: 0
}

/** Index of the current hour within [Hourly.time], or 0 when absent. */
fun nowIndex(hourly: Hourly?, currentTime: String?): Int {
    if (hourly == null || hourly.time.isEmpty()) return 0
    val hourPrefix = currentTime?.substring(0, 13)?.takeIf { it.length == 13 }
        ?: return 0
    return hourly.time.indexOfFirst { it.startsWith(hourPrefix) }.takeIf { it >= 0 } ?: 0
}
