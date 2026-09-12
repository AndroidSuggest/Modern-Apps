package com.vayunmathur.maps.ui

import android.content.Context
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.ui.FreeHeightSheetState
import com.vayunmathur.library.ui.Messenger
import com.vayunmathur.library.ui.OverlayAction
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.data.ParkingSpot
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.transit.TransitStop
import com.vayunmathur.maps.ipc.FamilyMember
import com.vayunmathur.maps.ui.map.MapChromeState
import com.vayunmathur.maps.util.DeparturesState
import com.vayunmathur.maps.util.MapSettingsViewModel
import com.vayunmathur.maps.util.MapsSearchViewModel
import com.vayunmathur.maps.util.NavigationProgress
import com.vayunmathur.maps.util.NavigationSessionManager
import com.vayunmathur.maps.util.ParkingViewModel
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SavedPlacesViewModel
import com.vayunmathur.maps.util.SearchResult
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import com.vayunmathur.maps.util.TransitStopsViewModel
import kotlinx.coroutines.CoroutineScope

/**
 * An open search sheet.
 *
 * Search used to be a nav destination, so these three lived in the route. They are still needed —
 * the bias centre because the Google search is biased toward what the user can see, and
 * [waypointIndex] because picking a result while editing a route replaces that leg rather than
 * selecting a place — but now they are the identity of a sheet, not of a page. `null` is the
 * closed state, which is why the whole thing is one nullable value rather than a flag plus three
 * fields that only mean anything while the flag is set.
 */
internal data class SearchRequest(
    val waypointIndex: Int?,
    val nearLat: Double,
    val nearLon: Double,
)

/**
 * Everything MapContentBox closes over, bundled so the box is one function
 * instead of a second public composable with a thirty-parameter signature.
 *
 * A private holder rather than passing the pieces individually: the box is the
 * shared half of MapPage, not a screen of its own, and threading every local
 * through parameters would split the call site from the state it already owns.
 * Widening the scope (a public data class, a public function taking it) would
 * make this a second public composable in spirit, which the one-per-file rule
 * forbids — so both the holder and the function stay internal to the ui package.
 */
internal class MapPageScope(
    val context: Context,
    val backStack: NavBackStack<Route>,
    val viewModel: SelectedFeatureViewModel,
    val savedPlacesViewModel: SavedPlacesViewModel,
    val searchViewModel: MapsSearchViewModel,
    val settingsViewModel: MapSettingsViewModel,
    val parkingViewModel: ParkingViewModel,
    val transitViewModel: TransitStopsViewModel,
    val coroutineScope: CoroutineScope,
    val messenger: Messenger,
    val noResultsMessage: String,
    val chrome: MapChromeState,
    val camera: CameraState,
    val selectedFeature: SpecificFeature?,
    val inactiveNavigation: SpecificFeature.Route?,
    val route: Map<RouteService.TravelMode, RouteService.RouteType?>?,
    val userPosition: GeoPoint,
    val userBearing: Float?,
    val userHeadingAccuracy: Int,
    val savedPins: List<SavedPlace>,
    val parkingSpot: ParkingSpot?,
    val searchResults: List<SearchResult>,
    val searchRecents: List<String>,
    val selectedTransitStop: TransitStop?,
    val departuresState: DeparturesState,
    val familyMembers: List<FamilyMember>,
    val trafficEnabled: Boolean,
    val satelliteEnabled: Boolean,
    val safetyEnabled: Boolean,
    val transitEnabled: Boolean,
    val darkMap: Boolean,
    val navState: NavigationSessionManager.NavState,
    val navSession: NavigationSessionManager.NavSession,
    val navProgress: NavigationProgress?,
    val isNavigating: Boolean,
    val browsing: Boolean,
    val searchBarVisible: Boolean,
    val searchBarLiftPx: Int,
    val onSearchBarLift: (Int) -> Unit,
    val sheetState: FreeHeightSheetState?,
    val searchSheetState: FreeHeightSheetState?,
    val searchRequest: SearchRequest?,
    val openSearch: (String?, Int?) -> Unit,
    val settingsAction: List<OverlayAction>,
    val wide: Boolean,
)
