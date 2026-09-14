package com.vayunmathur.maps.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.ui.CompassCalibrationHint
import com.vayunmathur.library.ui.FreeHeightSheetState
import com.vayunmathur.library.ui.OverlayAction
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.TopAppBarOverlay
import com.vayunmathur.maps.R as MapsR
import com.vayunmathur.maps.data.ParkingSpot
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.ui.map.LayerToggles
import com.vayunmathur.maps.ui.map.MapChromeState
import com.vayunmathur.maps.ui.map.MapFabStack
import com.vayunmathur.maps.ui.map.MapOverlay
import com.vayunmathur.maps.ui.map.MapOverlays
import com.vayunmathur.maps.ui.map.MapSearchBar
import com.vayunmathur.maps.ui.map.MapSurface
import com.vayunmathur.maps.ui.map.NavigationCameraFollow
import com.vayunmathur.maps.ui.map.WaypointList
import com.vayunmathur.maps.ui.streetview.StreetViewPegman
import com.vayunmathur.maps.ui.theme.MapChromeMetrics
import com.vayunmathur.maps.util.visibleBoundsOrWorld
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The shared map box, extracted from MapPage so no ui file exceeds the
 * [FileLength] limit. See MapPageScope for why the scope exists.
 */

@Composable
internal fun MapPageScope.MapContentBox(
    modifier: Modifier = Modifier,
    sheetState: FreeHeightSheetState? = this.sheetState,
) {
            // No app bar. The map is the whole screen and every piece of chrome floats over it,
            // which is also what keeps the renderer's surface edge-to-edge — a padded parent here
            // is what used to leave a dead strip along the navigation bar.
            Box(Modifier.fillMaxSize()) {
                MapSurface(
                    camera = camera,
                    chrome = chrome,
                    viewModel = viewModel,
                    searchViewModel = searchViewModel,
                    transitViewModel = transitViewModel,
                    sheetState = sheetState,
                    selectedFeature = selectedFeature,
                    route = route?.get(chrome.selectedRouteType),
                    userPosition = userPosition,
                    userBearing = userBearing,
                    navProgress = navProgress,
                    searchResults = searchResults,
                    savedPlaces = savedPins,
                    parkingSpot = parkingSpot,
                    familyMembers = familyMembers,
                    trafficEnabled = trafficEnabled,
                    satelliteEnabled = satelliteEnabled,
                    safetyEnabled = safetyEnabled,
                    transitEnabled = transitEnabled,
                    darkBasemap = darkMap,
                )

                // A direct child of the map's own box, not the inset one below: the peg's
                // drop point is read in this box's coordinates and projected as-is.
                //
                // Gated on search separately from `browsing` because the sheets draw over
                // this box: while search is up, a drop in the bottom third lands on the sheet
                // rather than the map, and half a working drag is worse than no peg.
                if (browsing && searchRequest == null) StreetViewPegman(camera)

                // The chrome's inset, in one place. `windowInsetsPadding` *consumes* what it
                // applies, so the pieces below that inset themselves — the FAB stack, the scale
                // bar, the navigation overlay, the overlay bar — become no-ops rather than
                // insetting twice, and the waypoint list, which never did, picks up the status
                // bar it used to get from the app bar.
                Box(Modifier.windowInsetsPadding(WindowInsets.systemBars).fillMaxSize()) {
                    val routeFeature =
                        (selectedFeature as? SpecificFeature.Route) ?: inactiveNavigation
                    Column(Modifier.align(Alignment.TopCenter)) {
                        // Turn-by-turn puts its maneuver banner in this exact slot, so the bar
                        // stands down for the duration rather than floating over it.
                        if (!isNavigating) {
                            TopAppBarOverlay(
                                actions = settingsAction,
                                // The chips ride in the bar's title rather than in a row of their
                                // own below it. They bring their own filled containers, which is
                                // what keeps them readable over the map; the bar itself stays
                                // transparent, so the map runs straight through behind them.
                                title = {
                                    // Dropped while a route is up: the waypoint list takes that
                                    // space and filtering POIs is not what you are doing then —
                                    // the same rule the chips followed before they moved. The
                                    // slot stays, so the settings button does not shift.
                                    if (routeFeature == null) {
                                        CategoryChips(
                                            onCategory = { chrome.toggleCategory(it) },
                                            selected = chrome.selectedCategory,
                                            // Inside the scroll, never as a margin: the chips have
                                            // to slide past the screen inset rather than clip
                                            // against it. The bar gives its title no inset of its
                                            // own precisely so this can be the only one.
                                            contentPadding = PaddingValues(
                                                start = Spacing.lg,
                                                end = Spacing.sm,
                                            ),
                                        )
                                    }
                                },
                            )
                        }
                        if (routeFeature != null) {
                            WaypointList(
                                route = routeFeature,
                                onReorder = { viewModel.set(it) },
                                onEditWaypoint = { index -> openSearch(null, index) },
                            )
                        } else {
                            // The quiet variant: a hint over the map, not a card. See
                            // [CompassCalibrationHint].
                            CompassCalibrationHint(
                                accuracy = userHeadingAccuracy,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(top = Spacing.sm),
                            )
                        }
                    }

                    // The search entry point, where the top app bar's used to be but at the other
                    // end of the screen. Browse-only: an open sheet — either of them — owns the
                    // bottom and brings its own field or its own title.
                    if (searchBarVisible) {
                        MapSearchBar(
                            onOpenSearch = { query -> openSearch(query, null) },
                            onContactAddress = { address ->
                                val bbox = camera.visibleBoundsOrWorld()
                                searchViewModel.resolveAndSelect(
                                    address,
                                    (bbox.north + bbox.south) / 2.0,
                                    (bbox.east + bbox.west) / 2.0,
                                ) { place ->
                                    if (place != null) {
                                        viewModel.stashRouteSelection()
                                        // Fly there and open the peek pane, the same direct-open
                                        // path a geo:/maps deep link takes.
                                        viewModel.selectAndFocus(
                                            place,
                                            zoom = (camera.position.zoom).coerceAtLeast(14.0),
                                        )
                                    } else {
                                        messenger.show(noResultsMessage)
                                    }
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                // Measured, not assumed: the bar's height is what the FAB stack
                                // and the scale bar have to clear, and it moves with the font
                                // scale. Outside the padding, so the margin is included.
                                .onSizeChanged { onSearchBarLift(it.height) }
                                .padding(MapChromeMetrics.chromeMargin),
                        )
                    }

                    // Browse controls, plus the layers and settings buttons, which stay out while
                    // a place is selected and ride above the sheet — see [MapFabStack].
                    // GAP (deferred, camera is target+zoom only): bearing is always 0
                    // north-up, so the compass hides itself and reset-north is a no-op.
                    MapFabStack(
                        camera = camera,
                        bearing = 0.0,
                        browsing = browsing,
                        // Whichever of the three is occupying the bottom: at most one sheet is
                        // ever up, and the search bar is only drawn when neither is.
                        // In the wide host the sheets never measure, so the lift only
                        // clears the search bar.
                        lift = {
                            (if (searchBarVisible) searchBarLiftPx.toFloat() else 0f).roundToInt()
                        },
                        // GAP (deferred, camera is target+zoom only): nothing to reset —
                        // the map is always north-up. Kept so the control slot survives.
                        onResetNorth = {},
                        onLayers = { chrome.show(MapOverlay.Layers) },
                        onParking = {
                            val spot = parkingSpot
                            if (spot == null) {
                                val position = userPosition
                                if (position.latitude != 0.0 || position.longitude != 0.0) {
                                    parkingViewModel.saveParking(position.latitude, position.longitude)
                                }
                            } else {
                                coroutineScope.launch {
                                camera.animateTo(
                                        camera.position.copy(
                                            target = GeoPoint(spot.lon, spot.lat),
                                            zoom = (camera.position.zoom).coerceAtLeast(15.0),
                                        )
                                    )
                                }
                                chrome.show(MapOverlay.Parking)
                            }
                        },
                        onMyLocation = {
                            coroutineScope.launch {
                                camera.animateTo(
                                    camera.position.copy(
                                        target = userPosition,
                                        zoom = (camera.position.zoom).coerceAtLeast(15.0),
                                    )
                                )
                            }
                        },
                    )

                    NavigationOverlay(
                        navState = navState,
                        steps = navSession.route?.step ?: emptyList(),
                        autoFollow = chrome.autoFollow,
                        onRecenter = { chrome.autoFollow = true },
                        onEndTrip = {
                            stopNavigation(context)
                            chrome.autoFollow = true
                            chrome.northUp = false
                        },
                        onDismissArrival = { stopNavigation(context) },
                        postedLimit = chrome.postedLimit,
                        northUp = chrome.northUp,
                        // GAP (deferred, camera is target+zoom only): heading-up follow
                        // is unsupported — the map is always north-up. The toggle slot is
                        // kept so the nav chrome survives; it records the preference for
                        // when the renderer can rotate.
                        onToggleNorthUp = { chrome.northUp = !chrome.northUp },
                        destinationName = navSession.destinationName,
                        darkBasemap = darkMap,
                        route = navSession.route,
                    )

                    MapOverlays(
                        overlay = chrome.overlay,
                        onDismiss = { chrome.dismissOverlay() },
                        layers = LayerToggles(
                            traffic = trafficEnabled,
                            satellite = satelliteEnabled,
                            safety = safetyEnabled,
                            transit = transitEnabled,
                            onTraffic = { settingsViewModel.setTrafficLayer(it) },
                            onSatellite = { settingsViewModel.setSatelliteLayer(it) },
                            onSafety = { settingsViewModel.setSafetyLayer(it) },
                            onTransit = { settingsViewModel.setTransitLayer(it) },
                        ),
                        parkingSpot = parkingSpot,
                        onClearParking = {
                            parkingViewModel.clear()
                            chrome.dismissOverlay()
                        },
                        onParkingDirections = {
                            val spot = parkingSpot
                            if (spot != null) {
                                val feature = spot.toFeature(context.getString(MapsR.string.parking_title))
                                viewModel.stashRouteSelection()
                                viewModel.set(SpecificFeature.Route(listOf(null, feature)))
                                chrome.dismissOverlay()
                            }
                        },
                        onParkingNoteChange = { parkingViewModel.updateNote(it) },
                        selectedStop = selectedTransitStop,
                        departures = departuresState,
                        onCloseStop = { transitViewModel.closeStop() },
                        onRefreshDepartures = { transitViewModel.refresh() },
                        // A tapped train opens its trip sheet, replacing the
                        // board (one tap, one back): the board's stop stays
                        // selected underneath so dismissing the trip returns
                        // to it rather than to the bare map.
                        onTrainTap = { dep ->
                            transitViewModel.openTripForDeparture(
                                dep,
                                selectedTransitStop?.lat ?: 0.0,
                                selectedTransitStop?.lon ?: 0.0,
                            )
                        },
                        tripSheet = tripItineraryState,
                        onCloseTrip = { transitViewModel.closeTrip() },
                        // A tapped station opens its departure board,
                        // replacing the trip sheet (one tap, one back).
                        onTripStopTap = { stop ->
                            transitViewModel.closeTrip()
                            transitViewModel.openNearestStop(
                                stop.lat,
                                stop.lon,
                                stop.name.ifBlank { null },
                            )
                        },
                    )
                }
            }
}

