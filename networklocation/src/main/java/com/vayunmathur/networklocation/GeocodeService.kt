package com.vayunmathur.networklocation

import android.app.Service
import android.content.Intent
import android.location.Address
import android.location.GeocoderParams
import android.os.IBinder
import com.android.location.provider.GeocodeProvider
import com.vayunmathur.networklocation.geocoder.GeoDbAssets
import com.vayunmathur.networklocation.geocoder.GeoDbReader
import com.vayunmathur.networklocation.geocoder.GeoResult
import java.util.Locale

/**
 * System geocoder backed entirely by the bundled offline planet DB — no network. Bound by the
 * framework via the `com.android.location.service.GeocodeProvider` action; the app must be the
 * configured geocode provider (config_geocodeProviderPackageName) and hold INSTALL_LOCATION_PROVIDER.
 *
 * The provider contract: return null on success (results appended to `addrs`) or an error string.
 */
class GeocodeService : Service() {
    private var db: GeoDbReader? = null

    private val provider: GeocodeProvider by lazy {
        object : GeocodeProvider() {
            override fun onGetFromLocation(
                latitude: Double,
                longitude: Double,
                maxResults: Int,
                params: GeocoderParams,
                addrs: MutableList<Address>,
            ): String? {
                val reader = db ?: return "geocoder database unavailable"
                val r = reader.reverse(latitude, longitude) ?: return null
                addrs.add(r.toAddress(params.locale))
                return null
            }

            override fun onGetFromLocationName(
                locationName: String,
                lowerLeftLatitude: Double,
                lowerLeftLongitude: Double,
                upperRightLatitude: Double,
                upperRightLongitude: Double,
                maxResults: Int,
                params: GeocoderParams,
                addrs: MutableList<Address>,
            ): String? {
                val reader = db ?: return "geocoder database unavailable"
                for (r in forwardSearch(reader, locationName, maxResults)) {
                    addrs.add(r.toAddress(params.locale))
                }
                return null
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = GeoDbAssets.open(this)
    }

    override fun onBind(intent: Intent?): IBinder? = provider.binder

    override fun onDestroy() {
        db?.close()
        db = null
        super.onDestroy()
    }

    /**
     * Best-effort freeform forward geocoding over structured data. The DB indexes by
     * country/state/city/street, so we map the trailing comma-separated components of the query
     * onto those fields. Full natural-language parsing is a known limitation.
     */
    private fun forwardSearch(db: GeoDbReader, query: String, maxResults: Int): List<GeoResult> {
        val parts = query.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 4) return emptyList()
        val country = parts[parts.size - 1]
        val state = parts[parts.size - 2]
        val city = parts[parts.size - 3]
        val street = parts[parts.size - 4]
        return db.forward(country, state, city, street, limit = maxResults.coerceIn(1, 50))
    }

    private fun GeoResult.toAddress(locale: Locale): Address {
        val a = Address(locale)
        a.latitude = lat
        a.longitude = lon
        val line = listOf(house, street).filter { it.isNotEmpty() }.joinToString(" ")
        if (line.isNotEmpty()) a.setAddressLine(0, line)
        if (house.isNotEmpty()) a.featureName = house
        if (street.isNotEmpty()) a.thoroughfare = street
        if (city.isNotEmpty()) a.locality = city
        if (state.isNotEmpty()) a.adminArea = state
        if (postcode.isNotEmpty()) a.postalCode = postcode
        if (country.isNotEmpty()) a.countryCode = country
        return a
    }
}
