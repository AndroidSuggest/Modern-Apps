package com.vayunmathur.weather.platform

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Best-effort reverse-geocode of a coordinate to a displayable place name.
 * Returns the city ([Address.getLocality], falling back to sub-admin then
 * admin area) plus the country, or null when the backend is missing or
 * yields nothing. Runs the blocking pre-33 lookup on [Dispatchers.IO].
 */
suspend fun reverseGeocodeName(context: Context, latitude: Double, longitude: Double): Pair<String, String>? =
    withContext(Dispatchers.IO) {
        runCatching {
            if (!Geocoder.isPresent()) return@runCatching null
            val geocoder = Geocoder(context)
            val addresses: List<Address> = if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (!cont.isCompleted) cont.resume(addresses)
                        }

                        override fun onError(errorMessage: String?) {
                            if (!cont.isCompleted) cont.resume(emptyList())
                        }
                    })
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(latitude, longitude, 1).orEmpty()
            }
            val address = addresses.firstOrNull() ?: return@runCatching null
            val place = listOfNotNull(address.locality, address.subAdminArea, address.adminArea)
                .firstOrNull { it.isNotBlank() } ?: return@runCatching null
            place to (address.countryName.orEmpty())
        }.getOrNull()
    }
