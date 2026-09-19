package com.vayunmathur.weather.service.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.vayunmathur.weather.data.WeatherRepository
import com.vayunmathur.weather.domain.roundCoord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The Car App Library [Session] for weather.
 *
 * Owns one [WeatherCarState] snapshot filled from the Room cache owned by
 * [WeatherRepository] — the same rows the phone UI renders. Locations arrive
 * via the DAO flow; each location's forecast JSON is decoded from
 * `WeatherCache` as it lands. Nothing here fetches from the network; a
 * missing forecast shows the loading state and the phone app's refresh worker
 * fills the cache in the background.
 *
 * Location permission is requested on first screen creation so the device's
 * "current location" row resolves in the list.
 */
class WeatherCarSession : Session() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null
    private lateinit var state: WeatherCarState

    override fun onCreateScreen(intent: Intent): Screen {
        state = WeatherCarState()
        requestLocationPermission()
        observeSnapshot()

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                collectJob?.cancel()
                scope.cancel()
            }
        })

        return WeatherCarLocationsScreen(carContext, state)
    }

    private fun requestLocationPermission() {
        val granted = ContextCompat.checkSelfPermission(
            carContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                carContext,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        runCatching {
            carContext.requestPermissions(
                listOf(Manifest.permission.ACCESS_FINE_LOCATION),
            ) { _, rejected ->
                state.permissionDenied = rejected.isNotEmpty()
                state.notifyChanged()
            }
        }
    }

    private fun observeSnapshot() {
        val repository = WeatherRepository.get(carContext)
        collectJob = scope.launch {
            repository.locations.collectLatest { locations ->
                state.locations = locations
                state.notifyChanged()
                // Decode each location's cached forecast off the DAO snapshot.
                // Sequential and IO-bound; the Room cache keeps it small.
                launch(Dispatchers.IO) {
                    for (location in locations) {
                        val cache = repository.getCache(
                            roundCoord(location.latitude),
                            roundCoord(location.longitude),
                        )
                        if (cache != null) {
                            decodeCarForecast(
                                cache.forecastJson,
                                cache.airQualityJson,
                                cache.fetchedAtEpochMs,
                            )?.let { state.putForecast(location.id, it) }
                        }
                    }
                    launch(Dispatchers.Main.immediate) { state.notifyChanged() }
                }
            }
        }
    }
}
