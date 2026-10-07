package com.vayunmathur.weather.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * On-device place catalogue response (`GeoDatabase.searchPlaces`), with the
 * same result fields city search has always shown.
 */
@Serializable
data class GeocodingResponse(val results: List<GeocodingResult> = emptyList())

@Serializable
data class GeocodingResult(
    val id: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val country: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    @SerialName("admin1") val admin1: String? = null,
    val timezone: String? = null,
)
