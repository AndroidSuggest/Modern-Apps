package com.vayunmathur.weather.platform

import android.content.Context
import com.vayunmathur.weather.R
import com.vayunmathur.weather.domain.TemperatureUnit
import com.vayunmathur.weather.domain.formatTemperatureCompact
import com.vayunmathur.weather.domain.todayIndex
import com.vayunmathur.weather.domain.weatherConditionForCode
import com.vayunmathur.weather.network.ForecastResponse
import java.util.Locale

/**
 * Rule-based one-paragraph human summary of today's forecast. Same role as
 * WeatherMaster's `computeDaySummary`, but synthesized purely from the
 * data we already have — no LLM, no extra network calls. Deterministic so
 * the same input always produces the same string.
 */
fun computeDaySummary(context: Context, forecast: ForecastResponse, tempUnit: TemperatureUnit): String {
    val current = forecast.current
    val daily = forecast.daily
    val conditionLabel = current?.weatherCode?.let {
        context.getString(weatherConditionForCode(it).label).lowercase(Locale.getDefault())
    } ?: context.getString(R.string.summary_mixed_conditions)
    val t = todayIndex(daily, current?.time)
    val hi = daily?.temperatureMax?.getOrNull(t)
    val lo = daily?.temperatureMin?.getOrNull(t)
    val precip = daily?.precipitationProbabilityMax?.getOrNull(t) ?: 0
    val wind = current?.windSpeed

    val parts = mutableListOf<String>()
    parts.add(context.getString(R.string.summary_expect_conditions, conditionLabel))
    if (hi != null && lo != null) {
        parts.add(
            context.getString(
                R.string.summary_high_low,
                formatTemperatureCompact(hi, tempUnit),
                formatTemperatureCompact(lo, tempUnit),
            )
        )
    }
    when {
        precip >= RAIN_LIKELY_PERCENT -> parts.add(context.getString(R.string.summary_rain_likely))
        precip >= SHOWERS_POSSIBLE_PERCENT ->
            parts.add(context.getString(R.string.summary_showers_possible))
        precip >= SLIGHT_CHANCE_PERCENT ->
            parts.add(context.getString(R.string.summary_slight_chance_rain))
    }
    if (wind != null && wind >= WINDY_KMH) {
        parts.add(context.getString(R.string.summary_winds_noticeable))
    }
    return parts.joinToString(" ")
}

/** Precipitation probability (%) at or above which rain is "likely". */
private const val RAIN_LIKELY_PERCENT = 70

/** Precipitation probability (%) at or above which showers are "possible". */
private const val SHOWERS_POSSIBLE_PERCENT = 40

/** Precipitation probability (%) at or above which rain gets a "slight chance" mention. */
private const val SLIGHT_CHANCE_PERCENT = 20

/** Wind speed (km/h) at or above which the summary mentions noticeable winds. */
private const val WINDY_KMH = 30.0
