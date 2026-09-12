package com.vayunmathur.auto.protocol

/**
 * Facts the host-vs-mirror prototype compares (Phase 6, maps-dev).
 *
 * The two paths render the same drive two different ways:
 *
 * - MIRROR: MA Auto draws the `:library:map` basemap itself (own
 *   `SurfaceMapRenderer`, camera, puck and route) into the ch2 video stream.
 *   The pixels are ours end to end; the head unit only ever sees H.264.
 * - HOST: the `:maps` car app (`MapsCarAppService`, a Car App Library
 *   `NAVIGATION` service) renders through a car-app host, and MA Auto would
 *   carry the host's surface into ch2 instead of drawing its own map.
 *
 * All fields are plain data gathered on the Android side by
 * `GearheadHostProbe`; the recommendation itself is pure so it stays
 * host-testable here.
 */
data class HostFacts(
    /** The `:maps` car-app service resolves through PackageManager. */
    val mapsServicePresent: Boolean,
    /** Our package holds `SYSTEM_AUTOMOTIVE_PROJECTION` (trusted display + injection). */
    val holdsProjectionRole: Boolean,
    /** The car-app host connection (`app-projected`) is on the classpath. */
    val hostLibraryPresent: Boolean,
)

data class MirrorFacts(
    /** `:library:map`'s renderer class loads (native Vulkan still gates first frame). */
    val rendererPresent: Boolean,
    /** Phone GPS is available for the puck and camera. */
    val locationAvailable: Boolean,
    /** An active route is overlaid (guidance), or null when idle map only. */
    val routeActive: Boolean,
)

//** Which path the session should render maps through. */
enum class MapsPath {
    /** Draw our own basemap into ch2; host stays a prototype. */
    MIRROR,

    /** Carry the car-app host's surface into ch2 instead. */
    HOST,
}

/**
 * Pure host-vs-mirror recommendation (Phase 6 prototype comparison).
 *
 * Mirror wins by default and host needs a measured win, per the plan:
 * the head unit only ever sees our ch2 H.264, so the host path adds a
 * second renderer, a service bind and a template round-trip without
 * changing a single wire byte. Host is recommended only when every host
 * fact holds AND the mirror cannot draw (no renderer, no location): a
 * host surface with no position is still a map, while no renderer at all
 * is a black card.
 */
object HostVsMirror {

    fun recommend(host: HostFacts, mirror: MirrorFacts): MapsPath {
        if (mirror.rendererPresent) return MapsPath.MIRROR
        val hostReady = host.mapsServicePresent &&
            host.holdsProjectionRole &&
            host.hostLibraryPresent
        if (hostReady && !mirror.locationAvailable) return MapsPath.HOST
        return MapsPath.MIRROR
    }
}
