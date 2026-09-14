package com.vayunmathur.maps.ui.map

import androidx.compose.runtime.Composable
import com.vayunmathur.maps.data.ParkingSpot
import com.vayunmathur.maps.data.transit.TransitStop
import com.vayunmathur.maps.data.transit.TripStop
import com.vayunmathur.maps.data.transit.Departure
import com.vayunmathur.maps.ui.DeparturesSheet
import com.vayunmathur.maps.ui.LayersSheet
import com.vayunmathur.maps.ui.ParkingSheet
import com.vayunmathur.maps.ui.TripSheet
import com.vayunmathur.maps.util.DeparturesState
import com.vayunmathur.maps.util.TripItineraryState

/** The layer toggles, grouped so the signature does not carry eight loose parameters. */
class LayerToggles(
    val traffic: Boolean,
    val satellite: Boolean,
    val safety: Boolean,
    val transit: Boolean,
    val onTraffic: (Boolean) -> Unit,
    val onSatellite: (Boolean) -> Unit,
    val onSafety: (Boolean) -> Unit,
    val onTransit: (Boolean) -> Unit,
)

/**
 * The modal sheets over the map.
 *
 * A single `when` on [MapOverlay] rather than an `if` per boolean: the sheets are mutually
 * exclusive, and independent flags could not express that — two could be true at once and the
 * second would draw over the first.
 *
 * The departure board is deliberately NOT part of that `when`. It is driven by whether a stop is
 * selected, which is ViewModel state rather than a chrome choice, and it can legitimately coexist
 * with nothing else being open.
 */
@Composable
fun MapOverlays(
    overlay: MapOverlay,
    onDismiss: () -> Unit,
    layers: LayerToggles,
    parkingSpot: ParkingSpot?,
    onClearParking: () -> Unit,
    onParkingDirections: () -> Unit,
    onParkingNoteChange: (String) -> Unit,
    selectedStop: TransitStop?,
    departures: DeparturesState,
    onCloseStop: () -> Unit,
    onRefreshDepartures: () -> Unit,
    onTrainTap: (Departure) -> Unit,
    tripSheet: TripItineraryState,
    onCloseTrip: () -> Unit,
    onTripStopTap: (TripStop) -> Unit,
) {
    when (overlay) {
        MapOverlay.None -> Unit

        // Map-layers toggle sheet (P6), opened from the LayersButton.
        MapOverlay.Layers -> LayersSheet(
            onDismiss = onDismiss,
            trafficEnabled = layers.traffic,
            onTrafficChange = layers.onTraffic,
            satelliteEnabled = layers.satellite,
            onSatelliteChange = layers.onSatellite,
            safetyEnabled = layers.safety,
            onSafetyChange = layers.onSafety,
            transitEnabled = layers.transit,
            onTransitChange = layers.onTransit,
        )

        // Parking sheet (P9): saved time + note, clear, and directions back to the car
        // through the existing routing path. Nothing to show without a saved spot.
        MapOverlay.Parking -> parkingSpot?.let { spot ->
            ParkingSheet(
                spot = spot,
                onDismiss = onDismiss,
                onClear = onClearParking,
                onDirections = onParkingDirections,
                onNoteChange = onParkingNoteChange,
            )
        }
    }

    // Departure board (P10): opened by tapping a transit stop. Live board from Transitous
    // (online-only); dismiss clears the selection. A tapped train opens its
    // trip sheet, replacing the board (one tap, one back).
    if (selectedStop != null) {
        DeparturesSheet(
            state = departures,
            onDismiss = onCloseStop,
            onRefresh = onRefreshDepartures,
            onTrainTap = onTrainTap,
        )
    }

    // Vehicle trip sheet: opened by tapping a vehicle sprite or a train on a
    // board. A tapped station opens its departure board, replacing this sheet.
    // Shown whenever a trip is selected, including over the board it came from.
    if (tripSheet is TripItineraryState.Loaded ||
        tripSheet is TripItineraryState.Loading ||
        tripSheet is TripItineraryState.Missing
    ) {
        TripSheet(
            state = tripSheet,
            onDismiss = onCloseTrip,
            onStopTap = onTripStopTap,
        )
    }
}
