package com.vayunmathur.weather.service.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.RowSection
import androidx.car.app.model.SectionedItemTemplate
import androidx.car.app.model.Template
import com.vayunmathur.weather.domain.TemperatureUnit
import com.vayunmathur.weather.domain.formatTemperatureCompact
import com.vayunmathur.weather.domain.weatherConditionForCode

/**
 * Saved locations list: every pinned place plus the device-location row.
 *
 * Each row shows the current temperature (when its forecast snapshot has
 * landed) over the condition label resolved from the cached WMO code — the
 * same label the phone UI shows. Tapping a row with a loaded forecast pushes
 * [WeatherCarDetailScreen]; rows still loading are inert until their snapshot
 * arrives. Empty/error/loading states use [MessageTemplate] so the 5-template
 * task quota ends on an allowed template.
 */
class WeatherCarLocationsScreen(
    carContext: CarContext,
    private val state: WeatherCarState,
) : Screen(carContext) {

    init {
        state.observe { invalidate() }
    }

    override fun onGetTemplate(): Template {
        val locations = state.locations
        if (locations == null) {
            return MessageTemplate.Builder("Loading saved locations…")
                .setLoading(true)
                .build()
        }
        if (locations.isEmpty()) {
            val builder = MessageTemplate.Builder(
                "No saved locations yet. Add places in the weather app on your phone.",
            ).setTitle("Weather")
            if (state.permissionDenied) {
                builder.addAction(
                    Action.Builder()
                        .setTitle("Retry location permission")
                        .setOnClickListener { requestPermissionAgain() }
                        .build(),
                )
            }
            return builder.build()
        }

        val tempUnit = TemperatureUnit.Celsius
        if (carContext.getCarAppApiLevel() >= 8) {
            return sectionedTemplate(locations.map { it to state.forecastFor(it.id) }, tempUnit)
        }
        return legacyListTemplate(locations.map { it to state.forecastFor(it.id) }, tempUnit)
    }

    private fun sectionedTemplate(
        entries: List<Pair<com.vayunmathur.weather.data.SavedLocation, CarForecast?>>,
        tempUnit: TemperatureUnit,
    ): Template {
        val limit = contentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        val section = RowSection.Builder()
        for ((location, snapshot) in entries.take(limit)) {
            section.addItem(locationRow(location, snapshot, tempUnit))
        }
        return SectionedItemTemplate.Builder()
            .addSection(section.build())
            .setHeader(
                androidx.car.app.model.Header.Builder()
                    .setTitle("Weather")
                    .build(),
            )
            .build()
    }

    private fun legacyListTemplate(
        entries: List<Pair<com.vayunmathur.weather.data.SavedLocation, CarForecast?>>,
        tempUnit: TemperatureUnit,
    ): Template {
        val limit = contentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        val list = ItemList.Builder()
        for ((location, snapshot) in entries.take(limit)) {
            list.addItem(locationRow(location, snapshot, tempUnit))
        }
        return ListTemplate.Builder()
            .setSingleList(list.build())
            .setTitle("Weather")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun locationRow(
        location: com.vayunmathur.weather.data.SavedLocation,
        snapshot: CarForecast?,
        tempUnit: TemperatureUnit,
    ): Row {
        val forecast = snapshot?.forecast
        val current = forecast?.current
        val title = if (location.isCurrent) {
            "${location.name.ifBlank { "Current location" }} · Here"
        } else {
            location.name
        }
        val row = Row.Builder().setTitle(title)
        if (location.country.isNotBlank()) {
            row.addText(location.country)
        }
        if (current != null) {
            row.addText(
                "${formatTemperatureCompact(current.temperature, tempUnit)} · " +
                    "${carContext.getString(weatherConditionForCode(current.weatherCode).label)}",
            )
        } else {
            row.addText("Loading forecast…")
        }
        if (forecast != null) {
            row.setBrowsable(true)
            row.setOnClickListener {
                screenManager.push(WeatherCarDetailScreen(carContext, location.id, state))
            }
        }
        return row.build()
    }

    private fun contentLimit(type: Int): Int {
        return runCatching {
            carContext.getCarService(ConstraintManager::class.java).getContentLimit(type)
        }.getOrDefault(DEFAULT_LIST_LIMIT)
    }

    private fun requestPermissionAgain() {
        runCatching {
            carContext.requestPermissions(
                listOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
            ) { _, rejected ->
                state.permissionDenied = rejected.isNotEmpty()
                state.notifyChanged()
            }
        }
    }

    private companion object {
        const val DEFAULT_LIST_LIMIT = 100
    }
}
