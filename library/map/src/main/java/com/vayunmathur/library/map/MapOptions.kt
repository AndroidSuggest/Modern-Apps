package com.vayunmathur.library.map

/**
 * Which pan/zoom/rotate gestures are enabled. Tilt (two-finger vertical drag) is always on
 * where the detector runs; rotation needs no separate detector, so it is one flag here.
 */
data class GestureOptions(
    val isScrollEnabled: Boolean = true,
    val isZoomEnabled: Boolean = true,
    val isRotateEnabled: Boolean = true,
) {
    companion object {
        /** Pan + zoom, rotation locked north-up (the old default behaviour). */
        val RotationLocked =
            GestureOptions(isScrollEnabled = true, isZoomEnabled = true, isRotateEnabled = false)

        /** All gestures disabled (static map). */
        val AllDisabled = GestureOptions(isScrollEnabled = false, isZoomEnabled = false)
    }
}

/**
 * Map ornaments.
 *
 * Attribution used to live here as an ornament, but task 51 (#9) removed the overlay from
 * `VectorMap` outright: the ODbL credit the map drew belongs on a host About/Legal screen
 * instead (see `ATTRIBUTION` in `VectorMap.kt` — no app shows it elsewhere yet, so the user
 * must place it). The flag is kept because it is part of the options object's shape; it
 * currently governs nothing, since attribution was the only ornament ever implemented.
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
    /**
     * Which optional layers (POI, transit) to draw. Both off by default — see
     * [LayerOptions] for why, and for what turning one on costs.
     */
    val layerOptions: LayerOptions = LayerOptions(),
)
