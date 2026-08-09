package com.vayunmathur.networklocation.provider

import android.content.Context
import android.os.WorkSource
import com.android.location.provider.LocationProviderBase
import com.android.location.provider.ProviderPropertiesUnbundled
import com.android.location.provider.ProviderRequestUnbundled

/**
 * The unbundled network location provider. The framework binds our
 * [NetworkLocationService], calls [onSetRequest] as clients start/stop requesting
 * location, and we push fixes back via [reportLocation].
 *
 * ALWAYS-ON: unlike GrapheneOS, this deliberately does NOT consult
 * `Settings.Global.network_location`. The provider is active whenever the OS master
 * Location switch is on and a client is requesting fixes; there is no separate
 * per-provider toggle to gate on.
 */
class LocationProviderImpl(context: Context) : LocationProviderBase(context, TAG, PROPERTIES) {
    private val task = LocationReportingTask(context) { reportLocation(it) }

    override fun onSetRequest(request: ProviderRequestUnbundled, source: WorkSource) {
        if (request.reportLocation) {
            task.start(request.interval)
        } else {
            task.stop()
        }
    }

    fun shutdown() = task.stop()

    private companion object {
        const val TAG = "NetworkLocationProvider"

        // Network provider: needs the network, no cost, coarse accuracy, low power.
        val PROPERTIES: ProviderPropertiesUnbundled = ProviderPropertiesUnbundled.create(
            /* requiresNetwork = */ true,
            /* requiresSatellite = */ false,
            /* requiresCell = */ false,
            /* hasMonetaryCost = */ false,
            /* supportsAltitude = */ false,
            /* supportsSpeed = */ false,
            /* supportsBearing = */ false,
            /* powerUsage = */ POWER_LOW,
            /* accuracy = */ ACCURACY_COARSE,
        )

        // Mirror android.location.Criteria constants without depending on them.
        const val POWER_LOW = 1
        const val ACCURACY_COARSE = 2
    }
}
