package com.vayunmathur.weather.network

import com.vayunmathur.library.network.NetworkClient

/**
 * Thin wrapper over the shared [NetworkClient] for the two Open-Meteo
 * endpoints we use. Open-Meteo requires no API key and returns JSON, so this
 * is just URL construction + delegation to the shared client.
 *
 * Everything is requested in metric units; the UI converts at display time so
 * toggling °C/°F doesn't trigger another HTTP call.
 */
object WeatherApi {

    private const val FORECAST_BASE = "https://api.open-meteo.com/v1/forecast"
    private const val GEOCODE_BASE = "https://geocoding-api.open-meteo.com/v1/search"
    private const val AIR_QUALITY_BASE = "https://air-quality-api.open-meteo.com/v1/air-quality"

    /**
     * Fetch the current conditions + 24h hourly + 7-day daily forecast for a
     * single coordinate. Throws on network / parse failure — callers wrap in
     * try/catch and fall back to cache.
     */
    suspend fun forecast(latitude: Double, longitude: Double): ForecastResponse {
        val url = buildString {
            append(FORECAST_BASE)
            append("?latitude=").append(latitude)
            append("&longitude=").append(longitude)
            append("&current=").append(
                listOf(
                    "temperature_2m",
                    "apparent_temperature",
                    "relative_humidity_2m",
                    "dew_point_2m",
                    "weather_code",
                    "wind_speed_10m",
                    "wind_direction_10m",
                    "pressure_msl",
                    "visibility",
                    "cloud_cover",
                    "wind_gusts_10m",
                    "is_day",
                ).joinToString(",")
            )
            append("&hourly=").append(
                listOf(
                    "temperature_2m",
                    "apparent_temperature",
                    "relative_humidity_2m",
                    "dew_point_2m",
                    "weather_code",
                    "precipitation_probability",
                    "precipitation",
                    "wind_speed_10m",
                    "wind_direction_10m",
                    "pressure_msl",
                    "visibility",
                    "uv_index",
                    "cloud_cover",
                    "wind_gusts_10m",
                    "is_day",
                ).joinToString(",")
            )
            append("&daily=").append(
                listOf(
                    "weather_code",
                    "temperature_2m_max",
                    "temperature_2m_min",
                    "apparent_temperature_max",
                    "apparent_temperature_min",
                    "sunrise",
                    "sunset",
                    "daylight_duration",
                    "sunshine_duration",
                    "uv_index_max",
                    "precipitation_probability_max",
                    "precipitation_sum",
                    "moon_phase",
                    "moonrise",
                    "moonset",
                ).joinToString(",")
            )
            append("&minutely_15=precipitation")
            append("&timezone=auto")
            append("&forecast_days=7")
        }
        return NetworkClient.getJson(url)
    }

    /**
     * Look up up to [limit] places that match the user-typed [query]. Used by
     * the in-app city search and by the OpenAssistant
     * `get_weather_by_name` intent.
     */
    suspend fun geocode(query: String, limit: Int = 5): GeocodingResponse {
        if (query.isBlank()) return GeocodingResponse(emptyList())
        val url = buildString {
            append(GEOCODE_BASE)
            append("?name=").append(java.net.URLEncoder.encode(query, "UTF-8"))
            append("&count=").append(limit)
            append("&format=json")
            append("&language=en")
        }
        return NetworkClient.getJson(url)
    }

    /**
     * Fetch just the current temperature (°C) for a coordinate. Used by the
     * city-search list to show the current temp next to each result. Requests
     * a single variable to keep these on-demand lookups lightweight.
     */
    suspend fun currentTemperature(latitude: Double, longitude: Double): Double? {
        val url = buildString {
            append(FORECAST_BASE)
            append("?latitude=").append(latitude)
            append("&longitude=").append(longitude)
            append("&current=temperature_2m")
            append("&forecast_days=1")
        }
        return NetworkClient.getJson<CurrentTemperatureResponse>(url).current?.temperature
    }

    /**
     * Resolve the IANA time zone for a coordinate via Open-Meteo's
     * `timezone=auto`. Requests a single trivial variable (the endpoint rejects
     * variable-less requests) and reads only the timezone fields. Used by the
     * map to label the zoomed-in region's local time. Throws on failure.
     */
    suspend fun timezoneAt(latitude: Double, longitude: Double): RegionTimezone {
        val url = buildString {
            append(FORECAST_BASE)
            append("?latitude=").append(latitude)
            append("&longitude=").append(longitude)
            append("&current=temperature_2m")
            append("&timezone=auto")
            append("&forecast_days=1")
        }
        return NetworkClient.getJson(url)
    }

    /**
     * Air quality + pollen for a coordinate. Best-effort: the air-quality
     * service has gaps outside Europe/North America, so callers should treat
     * the whole response as optional.
     */
    suspend fun airQuality(latitude: Double, longitude: Double): AirQualityResponse {
        val url = buildString {
            append(AIR_QUALITY_BASE)
            append("?latitude=").append(latitude)
            append("&longitude=").append(longitude)
            append("&current=").append(
                listOf(
                    "us_aqi",
                    "alder_pollen",
                    "birch_pollen",
                    "grass_pollen",
                    "mugwort_pollen",
                    "olive_pollen",
                    "ragweed_pollen",
                ).joinToString(",")
            )
            append("&timezone=auto")
        }
        return NetworkClient.getJson(url)
    }
}
