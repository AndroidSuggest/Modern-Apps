package com.vayunmathur.weather.service.car

import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.data.weatherJson
import com.vayunmathur.weather.domain.roundCoord
import com.vayunmathur.weather.network.AirQualityResponse
import com.vayunmathur.weather.network.ForecastResponse

/**
 * Shared car-session state, passed into every screen.
 *
 * This is a read-only snapshot of the Room cache owned by
 * [com.vayunmathur.weather.data.WeatherRepository] — the same rows the phone
 * UI and widgets render. The session fills it in asynchronously (locations
 * flow, per-location forecast JSON decode), so screens register for
 * [notifyChanged] and the session fires it as each source lands — a screen
 * rendered early refreshes instead of staying empty. Nothing here fetches
 * from the network; refresh stays in the phone app's `WeatherViewModel` and
 * `WeatherRefreshWorker`.
 */
class WeatherCarState {

    @Volatile var locations: List<SavedLocation>? = null
    @Volatile var permissionDenied: Boolean = false

    private val forecasts = mutableMapOf<Long, CarForecast>()
    private val observers = mutableSetOf<() -> Unit>()

    /** Forecast snapshot for one saved location, or null while unloaded. */
    @Synchronized
    fun forecastFor(locationId: Long): CarForecast? = forecasts[locationId]

    @Synchronized
    fun putForecast(locationId: Long, forecast: CarForecast) {
        forecasts[locationId] = forecast
    }

    @Synchronized
    fun observe(onChange: () -> Unit) {
        observers += onChange
    }

    @Synchronized
    fun notifyChanged() {
        val current = observers.toList()
        for (observer in current) {
            runCatching { observer() }
        }
    }
}

/** Decoded forecast payload for one location, mirrored from the Room cache row. */
data class CarForecast(
    val forecast: ForecastResponse,
    val airQuality: AirQualityResponse? = null,
    val fetchedAtEpochMs: Long = 0,
)

/**
 * Decode a [com.vayunmathur.weather.data.WeatherCache] row's JSON pair into a
 * [CarForecast]. Returns null when the forecast JSON fails to parse — the
 * caller treats that the same as "no cache yet" and shows the loading state.
 */
fun decodeCarForecast(
    forecastJson: String,
    airQualityJson: String?,
    fetchedAtEpochMs: Long,
): CarForecast? {
    val forecast = runCatching {
        weatherJson.decodeFromString<ForecastResponse>(forecastJson)
    }.getOrNull() ?: return null
    val air = airQualityJson?.let { json ->
        runCatching { weatherJson.decodeFromString<AirQualityResponse>(json) }.getOrNull()
    }
    return CarForecast(forecast = forecast, airQuality = air, fetchedAtEpochMs = fetchedAtEpochMs)
}

/** Cache key helper shared with the DAO write path (4-decimal rounding). */
fun cacheKeyFor(latitude: Double, longitude: Double): Pair<Double, Double> =
    roundCoord(latitude) to roundCoord(longitude)
