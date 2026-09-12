package com.vayunmathur.auto.platform

import android.content.ComponentName
import android.content.Context
import android.location.LocationManager
import android.util.Log
import com.vayunmathur.auto.protocol.HostFacts
import com.vayunmathur.auto.protocol.HostVsMirror
import com.vayunmathur.auto.protocol.MapsPath
import com.vayunmathur.auto.protocol.MirrorFacts

/**
 * Gathers the host-vs-mirror facts and renders the pure recommendation.
 *
 * Each lookup mirrors an existing seam in this module: the maps-service
 * lookup follows `CarApps.launcherApp` (which already resolves the
 * `com.vayunmathur.maps` tile, so this re-checks the car-app service inside
 * it), the role read follows `MaosRoleStatus`, and the host-library probe
 * is a `Class.forName` that never hard-links the car-app host -- the auto
 * module must not take that dependency until the host path is chosen.
 *
 * Runs on demand (one report per call), never watched: the recommendation
 * feeds the Phase 6 side-by-side report in `auto/docs/HANDOFF.md` §10 and
 * the phone debug row, not a live switch. Mirror is the session default
 * regardless; a HOST recommendation here is "prototype the host next", not
 * "reroute this session".
 */
object GearheadHostProbe {

    /**
     * Probes the device and recommends a maps path.
     *
     * @param routeActive whether a route is currently overlaid; from the
     *   guidance monitor's latest snapshot, never read from the maps module
     *   (no cross-module dependency, no shared process).
     */
    fun probe(context: Context, routeActive: Boolean = false): HostReport {
        val host = HostFacts(
            mapsServicePresent = hasMapsCarService(context),
            holdsProjectionRole = MaosRoleStatus.isProjectionRoleHeld(context),
            hostLibraryPresent = hasHostLibrary(),
        )
        val mirror = MirrorFacts(
            rendererPresent = hasMapRenderer(),
            locationAvailable = hasLocation(context),
            routeActive = routeActive,
        )
        return HostReport(host, mirror, HostVsMirror.recommend(host, mirror))
    }

    /**
     * The `:maps` car-app service (`MapsCarAppService`) is declared and
     * enabled. Package-qualified on purpose, like the music service string
     * in `MediaPlaybackMonitor`: no compile dependency on the maps module.
     */
    private fun hasMapsCarService(context: Context): Boolean = runCatching {
        val component = ComponentName(MAPS_PACKAGE, MAPS_CAR_SERVICE_CLASS)
        val info = context.packageManager.getServiceInfo(component, 0)
        info.enabled
    }.getOrDefault(false)

    /**
     * The car-app projected host is on the classpath.
     *
     * `Class.forName`, never an import: linking `androidx.car.app.projected`
     * from this module would drag the whole host stack into every auto
     * build for a path that is still a prototype. A missing class is a
     * fact (host not ready), not an error.
     */
    private fun hasHostLibrary(): Boolean = runCatching {
        Class.forName(HOST_CONNECTION_CLASS)
        true
    }.getOrDefault(false)

    /** `:library:map`'s renderer class loads. Native Vulkan still gates the first frame. */
    private fun hasMapRenderer(): Boolean = runCatching {
        Class.forName(MAP_RENDERER_CLASS)
        true
    }.getOrDefault(false)

    /** A location provider exists (enabled or not); permission still gates fixes. */
    private fun hasLocation(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        manager.getProviders(true).isNotEmpty() ||
            manager.allProviders.isNotEmpty()
    }.getOrDefault(false)

    /** One probe: the facts plus the pure recommendation. */
    data class HostReport(
        val host: HostFacts,
        val mirror: MirrorFacts,
        val path: MapsPath,
    ) {
        /** One-line verdict for logcat and the side-by-side report. */
        fun describe(): String =
            "maps path=$path " +
                "(host: service=${host.mapsServicePresent} " +
                "role=${host.holdsProjectionRole} lib=${host.hostLibraryPresent}; " +
                "mirror: renderer=${mirror.rendererPresent} " +
                "location=${mirror.locationAvailable} route=${mirror.routeActive})"

        /** Logs the verdict; call from the service bring-up on DEV builds only. */
        fun log() {
            Log.i(TAG, describe())
        }
    }

    private const val TAG = "MaAuto.Maps"

    private const val MAPS_PACKAGE = "com.vayunmathur.maps"
    private const val MAPS_CAR_SERVICE_CLASS = "com.vayunmathur.maps.car.MapsCarAppService"

    private const val HOST_CONNECTION_CLASS = "androidx.car.app.projected.CarAppProjectedService"

    private const val MAP_RENDERER_CLASS = "com.vayunmathur.library.map.SurfaceMapRenderer"
}
