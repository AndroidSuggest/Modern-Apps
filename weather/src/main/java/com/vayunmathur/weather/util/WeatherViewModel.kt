package com.vayunmathur.weather.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.glance.appwidget.updateAll
import com.vayunmathur.weather.R
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.data.WeatherDao
import com.vayunmathur.weather.data.WeatherRefreshWorker
import com.vayunmathur.weather.data.weatherJson
import com.vayunmathur.weather.data.writeForecastCache
import com.vayunmathur.weather.glance.WeatherGlanceWidget
import com.vayunmathur.weather.network.AirQualityResponse
import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.network.WeatherApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Per-location forecast state held in [WeatherViewModel.forecasts]. */
data class ForecastUiState(
    val forecast: ForecastResponse? = null,
    val airQuality: AirQualityResponse? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
    val fetchedAtEpochMs: Long = 0,
)

/**
 * A user-chosen hour or day to inspect. Identified by the Open-Meteo ISO
 * string (not an array index) so a refresh that shifts the arrays can't
 * corrupt the selection.
 */
sealed interface SelectedDateOrTime {
    /** [isoTime] matches a value in `Hourly.time`. */
    data class Time(val isoTime: String) : SelectedDateOrTime
    /** [isoDate] matches a value in `Daily.time`. */
    data class Day(val isoDate: String) : SelectedDateOrTime
}

/**
 * Holds saved locations, per-location forecast state, and the user's unit
 * prefs. Mirrors the manual-Factory pattern used everywhere else in this
 * repo (see [com.vayunmathur.passwords.util.PasswordsViewModel]).
 */
class WeatherViewModel(
    application: Application,
    private val dao: WeatherDao,
) : AndroidViewModel(application) {

    /**
     * Saved locations, or null until Room's first emission lands. The null
     * state matters: seeding this with `emptyList()` made a cold start render
     * the "no locations yet" chooser for a frame or two before the real rows
     * arrived, so the UI must be able to tell "still loading" apart from
     * "genuinely empty".
     */
    val savedLocations: StateFlow<List<SavedLocation>?> = dao.observeLocations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Per-location forecast state, keyed by [SavedLocation.id]. */
    private val _forecasts = MutableStateFlow<Map<Long, ForecastUiState>>(emptyMap())
    val forecasts: StateFlow<Map<Long, ForecastUiState>> = _forecasts.asStateFlow()

    /**
     * IDs with a refresh currently in flight. Guards against overlapping
     * fetches for the same location (e.g. a pull-to-refresh landing in the
     * same window as the 60-second poll), which the previous `refreshing`
     * flag couldn't do reliably because that flag was only set *inside* the
     * launched coroutine, after the guard had already been checked.
     */
    private val inFlight = mutableSetOf<Long>()

    /**
     * The hour/day the user is inspecting, or null for "now / today". Reset
     * whenever the active location changes (see [clearSelection]).
     */
    private val _selected = MutableStateFlow<SelectedDateOrTime?>(null)
    val selectedDateOrTime: StateFlow<SelectedDateOrTime?> = _selected.asStateFlow()

    fun selectTime(isoTime: String) { _selected.value = SelectedDateOrTime.Time(isoTime) }

    fun selectDay(isoDate: String) { _selected.value = SelectedDateOrTime.Day(isoDate) }

    fun clearSelection() { _selected.value = null }

    /** Select the hour, or clear it if it's already the selected one. */
    fun toggleTime(isoTime: String) {
        val current = _selected.value
        _selected.value = if (current is SelectedDateOrTime.Time && current.isoTime == isoTime) {
            null
        } else {
            SelectedDateOrTime.Time(isoTime)
        }
    }

    /** Select the day, or clear it if it's already the selected one. */
    fun toggleDay(isoDate: String) {
        val current = _selected.value
        _selected.value = if (current is SelectedDateOrTime.Day && current.isoDate == isoDate) {
            null
        } else {
            SelectedDateOrTime.Day(isoDate)
        }
    }

    init {
        WeatherRefreshWorker.scheduleHourlyRefresh(application)
    }

    /**
     * Ensure there's a fresh-enough forecast for [location].
     *
     * The 15-minute staleness decision lives here, so callers can poll
     * freely: hydrate from the on-disk cache first so the UI gets something
     * immediately, then kick off a background refresh only when the data is
     * missing/stale or [force] is set. Repeated calls while a refresh is in
     * flight are no-ops.
     */
    fun ensureForecast(location: SavedLocation, force: Boolean = false) {
        synchronized(inFlight) {
            if (location.id in inFlight) return
            // Fast path: already have fresh in-memory data, so there's nothing to do.
            val existing = _forecasts.value[location.id]
            val memStale = existing?.forecast == null ||
                (System.currentTimeMillis() - existing.fetchedAtEpochMs) >= STALE_THRESHOLD_MS
            if (!force && !memStale) return
            inFlight.add(location.id)
        }

        viewModelScope.launch {
            try {
                // 1. Hydrate from cache if we don't have anything in memory yet.
                //    Track whether that cache is itself still fresh so we can
                //    skip a redundant network round-trip on cold start.
                var haveFreshCache = false
                if (_forecasts.value[location.id]?.forecast == null) {
                    val cache = dao.getCache(roundCoord(location.latitude), roundCoord(location.longitude))
                    if (cache != null) {
                        runCatching { weatherJson.decodeFromString<ForecastResponse>(cache.forecastJson) }
                            .onSuccess { decoded ->
                                val cachedAir = cache.airQualityJson?.let { json ->
                                    runCatching { weatherJson.decodeFromString<AirQualityResponse>(json) }.getOrNull()
                                }
                                _forecasts.update { current ->
                                    current + (location.id to ForecastUiState(
                                        forecast = decoded,
                                        airQuality = cachedAir,
                                        refreshing = false,
                                        fetchedAtEpochMs = cache.fetchedAtEpochMs,
                                    ))
                                }
                                haveFreshCache = (System.currentTimeMillis() - cache.fetchedAtEpochMs) < STALE_THRESHOLD_MS
                            }
                    }
                }

                // 2. Skip the network when the (now hydrated) data is still fresh.
                //    Staleness is judged from the cache's own timestamp, not from
                //    whether we happened to have anything in memory — so opening
                //    the app no longer refetches every location's fresh data.
                if (!force && haveFreshCache) return@launch

                // 2b. For the device "current location" row, re-check the GPS fix now that we're
                //     committed to a network refresh, so the forecast follows the user if they've
                //     moved since the pin was created. Coordinates are updated in place (same row
                //     id), and the fetch below uses the fresh ones.
                val target = if (location.isCurrent) refreshDeviceLocationFix(location) else location

                // 3. Mark refreshing, then fetch forecast + air quality in parallel.
                _forecasts.update { current ->
                    val prev = current[location.id]
                    current + (location.id to (prev?.copy(refreshing = true) ?: ForecastUiState(refreshing = true)))
                }

                data class FetchResult(val forecast: kotlin.Result<ForecastResponse>, val air: AirQualityResponse?)
                val fetched: FetchResult = coroutineScope {
                    val forecastDeferred = async {
                        runCatching { WeatherApi.forecast(target.latitude, target.longitude) }
                    }
                    val airQualityDeferred = async {
                        runCatching { WeatherApi.airQuality(target.latitude, target.longitude) }.getOrNull()
                    }
                    FetchResult(forecastDeferred.await(), airQualityDeferred.await())
                }
                val forecastResult = fetched.forecast
                val airQuality = fetched.air

                forecastResult
                    .onSuccess { fresh ->
                        val now = System.currentTimeMillis()
                        // Keep previously-known air quality if this round's air-quality
                        // fetch failed, so a transient AQ error doesn't blank the block.
                        val resolvedAir = airQuality ?: _forecasts.value[location.id]?.airQuality
                        dao.writeForecastCache(target.latitude, target.longitude, fresh, resolvedAir, now)
                        _forecasts.update { current ->
                            current + (location.id to ForecastUiState(
                                forecast = fresh,
                                airQuality = resolvedAir,
                                refreshing = false,
                                error = null,
                                fetchedAtEpochMs = now,
                            ))
                        }
                        // Keep the home-screen widget in sync with a foreground refresh
                        // instead of leaving it stale until the next hourly worker run.
                        runCatching { WeatherGlanceWidget().updateAll(getApplication<Application>()) }
                    }
                    .onFailure { e ->
                        _forecasts.update { current ->
                            val prev = current[location.id]
                            // Forecast failed, but if air quality did come back, fold it
                            // in rather than discarding a successful fetch.
                            current + (location.id to (prev?.copy(
                                airQuality = airQuality ?: prev.airQuality,
                                refreshing = false,
                                error = e.message ?: "Failed to load forecast",
                            ) ?: ForecastUiState(
                                airQuality = airQuality,
                                refreshing = false,
                                error = e.message,
                            )))
                        }
                    }
            } finally {
                synchronized(inFlight) { inFlight.remove(location.id) }
            }
        }
    }

    /**
     * Refresh every saved location together, so the user never sees one
     * location freshly updated while others show "No data yet". Each location
     * hydrates from its on-disk cache immediately (populating the drawer's
     * "Last updated" timestamp) and then refreshes over the network.
     *
     * Per-location staleness gating in [ensureForecast] keeps this cheap:
     * locations refreshed within [STALE_THRESHOLD_MS] are skipped unless
     * [force] is set (pull-to-refresh), so a 60-second poll won't spam the
     * network.
     */
    fun refreshAll(force: Boolean = false) {
        for (location in savedLocations.value.orEmpty()) {
            ensureForecast(location, force)
        }
    }

    fun deleteLocation(location: SavedLocation) {
        viewModelScope.launch {
            dao.deleteLocation(location)
            _forecasts.update { it - location.id }
        }
    }

    /**
     * Persist a user-defined ordering of saved locations. [ordered] is the
     * full list in its new display order; each row's [SavedLocation.displayOrder]
     * is rewritten to its index.
     */
    fun reorderLocations(ordered: List<SavedLocation>) {
        viewModelScope.launch {
            ordered.forEachIndexed { index, loc ->
                if (loc.displayOrder != index) dao.setOrder(loc.id, index)
            }
        }
    }

    /**
     * Insert a manually-picked location at the end of the list. The current
     * row count drives [SavedLocation.displayOrder] so the new pin lands last.
     */
    fun addLocation(name: String, country: String, latitude: Double, longitude: Double) {
        viewModelScope.launch {
            val existing = dao.getLocations()
            dao.insertLocation(
                SavedLocation(
                    name = name,
                    country = country,
                    latitude = latitude,
                    longitude = longitude,
                    displayOrder = existing.size,
                    isCurrent = false,
                )
            )
        }
    }

    /** Replace (or insert) the single "current device" row with a fresh fix. */
    fun setCurrentLocation(name: String, latitude: Double, longitude: Double) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val defaultName = app.getString(R.string.current_location)
            dao.replaceCurrentDeviceLocation(
                SavedLocation(
                    name = name.ifBlank { defaultName },
                    country = "",
                    latitude = latitude,
                    longitude = longitude,
                    displayOrder = -1, // current row sorts first
                    isCurrent = true,
                )
            )
        }
    }

    /**
     * Re-queries the device location for the "current location" [location] and, if it has moved
     * more than [MIN_LOCATION_MOVE_METERS] from the stored fix, updates the row's coordinates in
     * place (keeping the same id). Returns the (possibly updated) location to fetch against.
     *
     * Best-effort: if permission is missing or no fix arrives within the timeout, the stored
     * coordinates are kept.
     */
    private suspend fun refreshDeviceLocationFix(location: SavedLocation): SavedLocation {
        val context = getApplication<Application>()
        if (!LocationProvider.hasPermission(context)) return location
        val fix = withTimeoutOrNull(LOCATION_FIX_TIMEOUT_MS) {
            LocationProvider.currentLocation(context)
        } ?: return location

        val distance = FloatArray(1)
        android.location.Location.distanceBetween(
            location.latitude, location.longitude, fix.latitude, fix.longitude, distance,
        )
        if (distance[0] < MIN_LOCATION_MOVE_METERS) return location

        dao.updateCoordinates(location.id, fix.latitude, fix.longitude)
        return location.copy(latitude = fix.latitude, longitude = fix.longitude)
    }

    companion object {
        /** Forecasts older than this are refreshed on the next poll. */
        const val STALE_THRESHOLD_MS = 15 * 60 * 1000L

        /** How long to wait for a device location fix when refreshing the current-location row. */
        private const val LOCATION_FIX_TIMEOUT_MS = 8_000L

        /** Ignore GPS jitter below this so we don't rewrite the row (and bust the cache) needlessly. */
        private const val MIN_LOCATION_MOVE_METERS = 500f
    }

}

class WeatherViewModelFactory(
    private val application: Application,
    private val dao: WeatherDao,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return WeatherViewModel(application, dao) as T
    }
}
