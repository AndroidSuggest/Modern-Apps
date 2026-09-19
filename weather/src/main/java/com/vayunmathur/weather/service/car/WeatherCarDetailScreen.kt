package com.vayunmathur.weather.service.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.domain.TemperatureUnit
import com.vayunmathur.weather.domain.compassDirection
import com.vayunmathur.weather.domain.formatTemperature
import com.vayunmathur.weather.domain.formatTemperatureCompact
import com.vayunmathur.weather.domain.formatWind
import com.vayunmathur.weather.domain.WindUnit
import com.vayunmathur.weather.domain.weatherConditionForCode
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Forecast detail for one saved location: now + today's high/low + the daily
 * outlook, all from the Room snapshot.
 *
 * Rows are plain text (condition label + temps + wind) so long lists stay
 * parked-safe with no host-side image loading. The pane is capped at the
 * host's `CONTENT_LIMIT_TYPE_PANE` so the template never fails validation on
 * strict hosts. When the snapshot hasn't landed yet this shows the loading
 * [MessageTemplate]; it can only be pushed from a row that had a forecast, so
 * a missing location resolves to the error message rather than a blank pane.
 */
class WeatherCarDetailScreen(
    carContext: CarContext,
    private val locationId: Long,
    private val state: WeatherCarState,
) : Screen(carContext) {

    init {
        state.observe { invalidate() }
    }

    override fun onGetTemplate(): Template {
        val location = state.locations?.firstOrNull { it.id == locationId }
        if (location == null) {
            return MessageTemplate.Builder("That location is no longer saved.")
                .setTitle("Weather")
                .setHeaderAction(Action.BACK)
                .build()
        }
        val snapshot = state.forecastFor(locationId)
        if (snapshot == null) {
            return MessageTemplate.Builder("Loading forecast…")
                .setTitle(location.name)
                .setHeaderAction(Action.BACK)
                .setLoading(true)
                .build()
        }
        return detailPane(location, snapshot)
    }

    private fun detailPane(location: SavedLocation, snapshot: CarForecast): Template {
        val tempUnit = TemperatureUnit.Celsius
        val forecast = snapshot.forecast
        val current = forecast.current
        val daily = forecast.daily
        val limit = runCatching {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PANE)
        }.getOrDefault(DEFAULT_PANE_LIMIT)

        val pane = Pane.Builder()
        if (current != null) {
            val now = Row.Builder()
                .setTitle(
                    "Now · ${formatTemperature(current.temperature, tempUnit)} · " +
                        "${carContext.getString(weatherConditionForCode(current.weatherCode).label)}",
                )
            now.addText(
                "Feels like ${formatTemperature(current.apparentTemperature, tempUnit)} · " +
                    "Humidity ${current.relativeHumidity}% · " +
                    "Wind ${formatWind(current.windSpeed, WindUnit.KmH)} " +
                    compassDirection(current.windDirection),
            )
            pane.addRow(now.build())
        }

        val days = daily?.time?.size ?: 0
        var rows = if (current != null) 1 else 0
        for (i in 0 until days) {
            if (rows >= limit) break
            val day = daily ?: break
            val isoDate = day.time.getOrNull(i) ?: break
            val hi = day.temperatureMax.getOrNull(i)
            val lo = day.temperatureMin.getOrNull(i)
            val code = day.weatherCode.getOrNull(i)
            val precip = day.precipitationProbabilityMax.getOrNull(i)
            val dayLabel = dayLabelFor(isoDate, i)
            val parts = mutableListOf<String>()
            if (hi != null && lo != null) {
                parts += "${formatTemperatureCompact(hi, tempUnit)} / " +
                    formatTemperatureCompact(lo, tempUnit)
            }
            if (code != null) {
                parts += carContext.getString(weatherConditionForCode(code).label)
            }
            if (precip != null && precip > 0) {
                parts += "$precip% rain"
            }
            pane.addRow(
                Row.Builder()
                    .setTitle(dayLabel)
                    .apply { if (parts.isNotEmpty()) addText(parts.joinToString(" · ")) }
                    .build(),
            )
            rows++
        }

        return PaneTemplate.Builder(pane.build())
            .setTitle(location.name.ifBlank { "Current location" })
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun dayLabelFor(isoDate: String, index: Int): String {
        if (index == 0) return "Today"
        return runCatching {
            LocalDate.parse(isoDate).dayOfWeek
                .getDisplayName(TextStyle.FULL, Locale.getDefault())
        }.getOrDefault(isoDate)
    }

    private companion object {
        const val DEFAULT_PANE_LIMIT = 20
    }
}
