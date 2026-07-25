package com.vayunmathur.library.map

/**
 * Which pan/zoom gestures are enabled. Rotation and tilt are unsupported (the
 * map is always north-up), so there are no toggles for them.
 */
data class GestureOptions(
    val isScrollEnabled: Boolean = true,
    val isZoomEnabled: Boolean = true,
) {
    companion object {
        /** Pan + zoom enabled; rotation/tilt unsupported anyway. */
        val RotationLocked = GestureOptions(isScrollEnabled = true, isZoomEnabled = true)

        /** All gestures disabled (static map). */
        val AllDisabled = GestureOptions(isScrollEnabled = false, isZoomEnabled = false)
    }
}

/**
 * Map ornaments. Only the attribution label is supported. CARTO/OSM terms
 * require visible attribution, so leave it enabled unless the host renders its
 * own (e.g. weather shows attribution in its bottom panel).
 */
data class OrnamentOptions(
    val isAttributionEnabled: Boolean = true,
) {
    companion object {
        val AllDisabled = OrnamentOptions(isAttributionEnabled = false)
    }
}

data class MapOptions(
    val gestureOptions: GestureOptions = GestureOptions(),
    val ornamentOptions: OrnamentOptions = OrnamentOptions(),
)
