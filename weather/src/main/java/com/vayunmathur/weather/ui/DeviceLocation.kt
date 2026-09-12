package com.vayunmathur.weather.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.weather.R
import com.vayunmathur.weather.platform.LocationProvider
import com.vayunmathur.weather.platform.WeatherViewModel
import kotlinx.coroutines.launch

/**
 * Composable helper that provides a device-location request action with
 * permission handling. Returns the onClick lambda and a loading flag.
 * Used by both [LocationsPage] and the empty-home state in `HomePage.kt`.
 */
@Composable
internal fun rememberRequestDeviceLocation(
    viewModel: WeatherViewModel,
): Pair<() -> Unit, Boolean> {
    val context = LocalContext.current
    val messenger = rememberMessenger()
    val scope = rememberCoroutineScope()
    val currentLocationLabel = stringResource(R.string.current_location)
    val couldntDetermineMsg = stringResource(R.string.couldn_t_determine_location)
    val permissionDeniedMsg = stringResource(R.string.location_permission_denied)
    var loading by remember { mutableStateOf(false) }

    val fetchLocation: () -> Unit = {
        loading = true
        scope.launch {
            val loc = LocationProvider.currentLocation(context)
            if (loc != null) {
                viewModel.setCurrentLocation(currentLocationLabel, loc.latitude, loc.longitude)
            } else {
                messenger.show(couldntDetermineMsg)
            }
            loading = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) {
            fetchLocation()
        } else {
            messenger.show(permissionDeniedMsg)
        }
    }

    val onClick = {
        if (LocationProvider.hasPermission(context)) {
            fetchLocation()
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }

    return onClick to loading
}
