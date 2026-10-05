package com.vayunmathur.communicate.service.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.annotations.ExperimentalCarApi
import androidx.car.app.annotations.RequiresCarApi
import androidx.car.app.validation.HostValidator

/**
 * Car App Library entry point for messaging + calls.
 *
 * Declared in the manifest with the `androidx.car.app.CarAppService` action
 * and the `androidx.car.app.category.MESSAGING` category, so car hosts (MA
 * Auto's launcher, Android Auto) list communicate in the dock. Threads and
 * calls stay single-sourced: screens read
 * [com.vayunmathur.communicate.data.loadSmsThreadsMerged] /
 * [com.vayunmathur.communicate.data.loadSmsMessagesMerged], reply through
 * [com.vayunmathur.communicate.data.sendMessage], and drive calls through
 * `placeCallForLine` + `InAppCallRegistry` — the same choke-points the
 * phone UI and the GAL ch14 mirror use.
 */
class CommunicateCarAppService : CarAppService() {

    private companion object {
        private const val CAR_API_MIN = 6
        private const val CAR_API_CONVERSATION = 9
    }

    // Dev posture: accept any host. Before shipping this must be tightened to a
    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = CommunicateCarSession()

    // API 6+: per-display sessions.
    @RequiresCarApi(CAR_API_MIN)
    override fun onCreateSession(sessionInfo: SessionInfo): Session = CommunicateCarSession()

    // API 9 (experimental): keep MA brand styling on hosts that offer it.
    @RequiresCarApi(CAR_API_CONVERSATION)
    @ExperimentalCarApi
    override fun getCarAppThemeSource(): Int =
        CarAppService.THEME_SOURCE_APP
}
