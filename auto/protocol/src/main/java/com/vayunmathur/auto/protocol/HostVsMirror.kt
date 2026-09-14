package com.vayunmathur.auto.protocol

/**
 * Host readiness for the nav card (host-only, no mirror comparison).
 *
 * The nav card renders whatever the `:maps` car app publishes through
 * `CarAppHost`: the app's own surface plus its `NavigationTemplate`. There is
 * no second render path to compare against, so this is a readiness record,
 * not a recommendation -- the card hosts when ready and shows the launch tile
 * otherwise.
 */
data class HostFacts(
    /** The `:maps` car-app service resolves through PackageManager. */
    val mapsServicePresent: Boolean,
    /** Our package holds `SYSTEM_AUTOMOTIVE_PROJECTION` (trusted display + injection). */
    val holdsProjectionRole: Boolean,
    /** The car-app host library (`androidx.car.app:app`) is on the classpath. */
    val hostLibraryPresent: Boolean,
)

/** Whether the nav card can host the maps app right now. */
object HostReadiness {
    fun ready(host: HostFacts): Boolean =
        host.mapsServicePresent && host.holdsProjectionRole && host.hostLibraryPresent
}
