package com.vayunmathur.weather.intents

import com.vayunmathur.library.intents.weather.WeatherData
import com.vayunmathur.library.util.AssistantIntent
import com.vayunmathur.weather.network.WeatherApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/** Input payload for [GetWeatherByNameIntent]: a free-form city/place name. */
@Serializable
data class LocationQueryInput(val name: String)

/**
 * "Headless" activity launched by OpenAssistant's `get_weather_by_name`
 * tool. Resolves [LocationQueryInput.name] via Open-Meteo's geocoding API,
 * then fetches a forecast for the first match. Returns [WeatherData] with
 * `error` set if no place matched or the network call failed.
 */
@OptIn(InternalSerializationApi::class)
class GetWeatherByNameIntent : AssistantIntent<LocationQueryInput, WeatherData>(
    inputSerializer = serializer<LocationQueryInput>(),
    outputSerializer = serializer<WeatherData>(),
) {
    override suspend fun performCalculation(input: LocationQueryInput): WeatherData {
        return try {
            val matches = WeatherApi.geocode(input.name, limit = 1).results
            val place = matches.firstOrNull()
                ?: return errorWeatherData(input.name, "No location matched '${input.name}'")
            val forecast = WeatherApi.forecast(place.latitude, place.longitude)
            val label = listOfNotNull(place.name, place.country)
                .filter { it.isNotBlank() }
                .joinToString(", ")
            forecast.toWeatherData(this, locationName = label.ifBlank { place.name })
        } catch (e: Exception) {
            errorWeatherData(input.name, e.message ?: "Failed to fetch forecast")
        }
    }
}
