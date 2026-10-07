package com.vayunmathur.weather.network

import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.weather.data.GeoDatabase

/**
 * Thin wrapper over the shared [NetworkClient] for the self-hosted `wx/v1`
 * bundle API on `api.vayunmathur.com` (+ self-hosted geocoding on the same
 * host). One `bundle()` call carries everything the weather screen needs;
 * the mapper normalizes it into the existing DTOs so nothing downstream of
 * `network/` changes.
 *
 * Everything is requested in metric units; the UI converts at display time so
 * toggling °C/°F doesn't trigger another HTTP call.
 */
object WeatherApi {

    private const val BUNDLE_BASE = "https://api.vayunmathur.com/wx/v1"
    private const val GEOCODE_BASE = "https://api.vayunmathur.com/wx/v1/search"

    /**
     * Fetch the full bundle (current + hourly + daily + 15-min + AQ) for a
     * single coordinate. Single HTTP call. Throws on network / parse failure
     * — callers wrap in try/catch and fall back to cache.
     */
    suspend fun bundle(latitude: Double, longitude: Double, fields: String = "all"): WxBundle {
        val url = buildString {
            append(BUNDLE_BASE).append("/bundle")
            append("?lat=").append(latitude)
            append("&lon=").append(longitude)
            append("&fields=").append(fields)
        }
        return NetworkClient.getJson(url)
    }

    /**
     * Fetch the current conditions + 24h hourly + 7-day daily forecast for a
     * single coordinate. Throws on network / parse failure — callers wrap in
     * try/catch and fall back to cache.
     */
    suspend fun forecast(latitude: Double, longitude: Double): ForecastResponse {
        return bundle(latitude, longitude).toForecastResponse()
    }

    /**
     * Look up up to [limit] places that match the user-typed [query]. Used by
     * the in-app city search and by the OpenAssistant
     * `get_weather_by_name` intent.
     *
     * On-device catalogue first (offline-capable, no network); the server
     * endpoint is the fallback for checkouts without the generated asset.
     */
    suspend fun geocode(query: String, limit: Int = 5): GeocodingResponse {
        if (query.isBlank()) return GeocodingResponse(emptyList())
        val local = runCatching {
            GeoDatabase.searchPlaces(query, limit)
        }.getOrDefault(emptyList())
        if (local.isNotEmpty()) return GeocodingResponse(local)
        // Catalogue absent (or genuinely no match): try the server index.
        val url = buildString {
            append(GEOCODE_BASE)
            append("?name=").append(java.net.URLEncoder.encode(query, "UTF-8"))
            append("&count=").append(limit)
        }
        return runCatching {
            NetworkClient.getJson<GeocodingResponse>(url)
        }.getOrDefault(GeocodingResponse(emptyList()))
    }

    /**
     * Fetch just the current temperature (°C) for a coordinate. Used by the
     * city-search list to show the current temp next to each result.
     * Requests only the `current` bundle section (~200-byte body).
     */
    suspend fun currentTemperature(latitude: Double, longitude: Double): Double? {
        return bundle(latitude, longitude, "current").current?.temp
    }

    /**
     * Batch current temperatures for search results: one round trip for all
     * rows instead of N parallel [currentTemperature] calls. Returns
     * temp-per-coordinate in request order; a failed point is absent (callers
     * keep the per-row spinner → blank, same as today's `getOrNull`).
     */
    suspend fun currentTemperatures(points: List<Pair<Double, Double>>): Map<Pair<Double, Double>, Double> {
        if (points.isEmpty()) return emptyMap()
        val url = buildString {
            append(BUNDLE_BASE).append("/current?points=")
            append(points.take(20).joinToString("|") { (lat, lon) -> "$lat,$lon" })
        }
        val batch: WxCurrentBatch = NetworkClient.getJson(url)
        return batch.points.associate { p -> (p.lat to p.lon) to p.temp }
    }

    /**
     * Resolve the IANA time zone for a coordinate. Requests only the envelope
     * (no weather data at all). Used by the map to label the zoomed-in
     * region's local time. Throws on failure.
     */
    suspend fun timezoneAt(latitude: Double, longitude: Double): RegionTimezone {
        return bundle(latitude, longitude, "tz").toRegionTimezone()
    }

    /**
     * Air quality + pollen for a coordinate. Best-effort: the service has gaps
     * outside Europe/North America, so callers should treat the whole response
     * as optional. Missing `aq` section maps to [AirQualityResponse](null),
     * exactly like today's nullable DTO.
     */
    suspend fun airQuality(latitude: Double, longitude: Double): AirQualityResponse {
        return bundle(latitude, longitude).toAirQuality() ?: AirQualityResponse(null)
    }
}
