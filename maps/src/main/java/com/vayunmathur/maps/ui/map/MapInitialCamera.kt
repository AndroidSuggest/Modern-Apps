package com.vayunmathur.maps.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.map.CameraPosition
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.rememberCameraState
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.maps.data.MapPreferences
import com.vayunmathur.maps.util.SelectedFeatureViewModel

/** Cold-start fallback: San Francisco at z14, where the baked POIs are dense enough to see. */
private val FALLBACK_CAMERA = CameraPosition(target = GeoPoint(-122.4194, 37.7749), zoom = 14.0)

/** Zoom the cold-start camera adopts once the user's position is known. */
private const val FIRST_FIX_ZOOM = 14.0

/**
 * Cold-start camera for the map screen.
 *
 * Seeded from the persisted last fix so the map opens where the user was, not
 * the SF fallback. DataStore's snapshot read can be empty on a truly cold
 * process (not yet hydrated), so the first live fix still centers below —
 * either way the zoom stays at the browse default.
 *
 * The centering runs once: it is skipped when a deep link or an existing
 * selection owns the camera, and latched in that case too, so consuming the
 * deep link later cannot re-trigger a center that would steal it back.
 */
@Composable
fun rememberInitialCamera(viewModel: SelectedFeatureViewModel): CameraState {
    val context = LocalContext.current
    val dataStore = remember(context) { DataStoreUtils.getInstance(context) }
    val snapshotLast = remember(dataStore) {
        val lon = dataStore.getDouble(MapPreferences.KEY_LAST_LON)
        val lat = dataStore.getDouble(MapPreferences.KEY_LAST_LAT)
        if (lon != null && lat != null && (lat != 0.0 || lon != 0.0)) GeoPoint(lon, lat) else null
    }
    val camera = rememberCameraState(
        snapshotLast?.let { CameraPosition(target = it, zoom = FIRST_FIX_ZOOM) } ?: FALLBACK_CAMERA,
    )

    val userPosition by viewModel.userPosition.collectAsState()
    val selectedFeature by viewModel.selectedFeature.collectAsState()
    val pendingFocus by viewModel.pendingFocus.collectAsState()
    var centeredOnFix by remember { mutableStateOf(false) }
    LaunchedEffect(userPosition, selectedFeature, pendingFocus) {
        if (centeredOnFix) return@LaunchedEffect
        if (userPosition.latitude == 0.0 && userPosition.longitude == 0.0) return@LaunchedEffect
        if (pendingFocus != null || selectedFeature != null) {
            centeredOnFix = true
            return@LaunchedEffect
        }
        centeredOnFix = true
        camera.animateTo(
            camera.position.copy(
                target = userPosition,
                zoom = FIRST_FIX_ZOOM.coerceAtLeast(camera.position.zoom),
            ),
        )
    }
    return camera
}
