package com.vayunmathur.auto.protocol

/**
 * Which display the car UI renders on.
 *
 * Today `CarDisplay` builds a *private* virtual display, which needs no permission —
 * that is why the DHU test works on a stock phone. Launching *other apps'* activities
 * onto the car display needs a trusted display (`ADD_TRUSTED_DISPLAY`), which arrives
 * with the `SYSTEM_AUTOMOTIVE_PROJECTION` role; input injection needs
 * `CREATE_VIRTUAL_DEVICE`, from the same role. See HANDOFF.md §8.
 */
enum class DisplayRouteKind {
    /** `VIRTUAL_DISPLAY_FLAG_PRESENTATION` on a private display. No permission needed. */
    PRIVATE_VIRTUAL,

    /** Trusted display: other apps' activities may be launched onto it. Needs the role. */
    TRUSTED,
}

/**
 * Decides which display route a session uses.
 *
 * Pure policy so it stays host-testable: private until the MAOS role grants the
 * trusted-display permission, then trusted so Maps / Music / dialer activities can
 * be launched onto the car screen instead of mirrored. The video path itself
 * (`CarDisplay` → `VideoEncoder` → ch2) is unchanged either way.
 */
object DisplayRoutePolicy {

    /**
     * @param holdsProjectionRole whether this package currently holds
     *   `android.app.role.SYSTEM_AUTOMOTIVE_PROJECTION`.
     */
    fun routeFor(holdsProjectionRole: Boolean): DisplayRouteKind =
        if (holdsProjectionRole) DisplayRouteKind.TRUSTED else DisplayRouteKind.PRIVATE_VIRTUAL
}
