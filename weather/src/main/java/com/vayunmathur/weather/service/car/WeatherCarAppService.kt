package com.vayunmathur.weather.service.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.annotations.ExperimentalCarApi
import androidx.car.app.annotations.RequiresCarApi
import androidx.car.app.validation.HostValidator

/**
 * Car App Library entry point for weather.
 *
 * Declared in the manifest with the `androidx.car.app.CarAppService` action
 * and the `androidx.car.app.category.WEATHER` category, so car hosts (MA
 * Auto's launcher, Android Auto) list weather in the dock. Forecast data is
 * never fetched here — the session observes the Room snapshot owned by
 * [com.vayunmathur.weather.data.WeatherRepository] (the same cache the phone
 * UI and widgets read), so car and phone stay in lockstep with one source.
 */
class WeatherCarAppService : CarAppService() {

    // Dev posture: accept any host. Before shipping this must be tightened to a
    // real allow-list (Android Auto / Automotive OS signatures) via
    // HostValidator.Builder + the car-app allowlist — same follow-up as maps/music.
    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = WeatherCarSession()

    // API 6+: per-display sessions. Cluster sessions still get the locations
    // list — the host picks which templates it allows.
    @RequiresCarApi(6)
    override fun onCreateSession(sessionInfo: SessionInfo): Session = WeatherCarSession()

    // API 9 (experimental): keep MA brand styling on hosts that offer it.
    @RequiresCarApi(9)
    @ExperimentalCarApi
    override fun getCarAppThemeSource(): Int =
        CarAppService.THEME_SOURCE_APP
}
