package com.vayunmathur.maps.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.CompassCalibrationHint
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FreeHeightBottomSheetScaffold
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.FreeHeightSheetState
import com.vayunmathur.library.ui.Messenger
import com.vayunmathur.library.ui.OverlayAction
import com.vayunmathur.library.ui.SheetValue
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.TopAppBarOverlay
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.library.ui.rememberFreeHeightSheetState
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.map.CameraPosition
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.MapBody
import com.vayunmathur.library.map.MoonTextures
import com.vayunmathur.library.map.rememberCameraState
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.data.ParkingSpot
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.transit.TransitStop
import com.vayunmathur.maps.ipc.FamilyMember
import com.vayunmathur.maps.ui.map.LayerToggles
import com.vayunmathur.maps.ui.map.MapFabStack
import com.vayunmathur.maps.ui.map.MapOverlay
import com.vayunmathur.maps.ui.map.MapOverlays
import com.vayunmathur.maps.ui.map.MapSearchBar
import com.vayunmathur.maps.ui.map.MapSurface
import com.vayunmathur.maps.ui.map.NavigationCameraFollow
import com.vayunmathur.maps.ui.map.WaypointList
import com.vayunmathur.maps.ui.map.rememberMapChromeState
import com.vayunmathur.maps.ui.streetview.StreetViewPegman
import com.vayunmathur.maps.ui.theme.MapChromeMetrics
import com.vayunmathur.maps.ui.map.MapChromeState
import com.vayunmathur.maps.util.DeparturesState
import com.vayunmathur.maps.data.parse
import com.vayunmathur.maps.util.MapTileCache
import com.vayunmathur.maps.util.OfflineRouter
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SavedPlacesViewModel
import com.vayunmathur.maps.util.GooglePoiMapViewModel
import com.vayunmathur.maps.util.MapSettingsViewModel
import com.vayunmathur.maps.util.MapsSearchViewModel
import com.vayunmathur.maps.util.MoonAssetLoader
import com.vayunmathur.maps.util.NavigationProgress
import com.vayunmathur.maps.util.NavigationSessionManager
import com.vayunmathur.maps.data.google.PoiSection
import com.vayunmathur.maps.util.PlacePanelActions
import com.vayunmathur.maps.util.PlacePanelState
import com.vayunmathur.maps.util.PoiIndex
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SavedPlacesViewModel
import com.vayunmathur.maps.util.SearchActions
import com.vayunmathur.maps.util.SearchUiState
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import com.vayunmathur.maps.util.visibleBoundsOrWorld
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position
import com.vayunmathur.library.ui.ReorderableItem
import com.vayunmathur.library.ui.draggableHandle
import com.vayunmathur.library.ui.rememberReorderableLazyListState
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.maps.R as MapsR

/** Cold-start camera: San Francisco at z14, where the baked POIs are dense enough to see. */
private val INITIAL_CAMERA = CameraPosition(target = GeoPoint(-122.4194, 37.7749), zoom = 14.0)


/**
 * The map screen.
 *
 * This composable is the wiring: it collects the ViewModel state, decides what the chrome should
 * show, and hands each piece to a stateless component in [com.vayunmathur.maps.ui.map]. The
 * pieces themselves — the search bar, the FAB stack, the sheets, the hit-test, the camera
 * follow, the style patch — each live in their own file and none of them know about this one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapPage(
    backStack: NavBackStack<Route>,
    viewModel: SelectedFeatureViewModel,
    savedPlacesViewModel: SavedPlacesViewModel,
    searchViewModel: MapsSearchViewModel,
    settingsViewModel: MapSettingsViewModel,
    parkingViewModel: com.vayunmathur.maps.util.ParkingViewModel,
    transitViewModel: com.vayunmathur.maps.util.TransitStopsViewModel,
) {
fun MapPage(backStack: NavBackStack<Route>, viewModel: SelectedFeatureViewModel, savedPlacesViewModel: SavedPlacesViewModel, poiViewModel: GooglePoiMapViewModel, searchViewModel: MapsSearchViewModel, settingsViewModel: MapSettingsViewModel, parkingViewModel: com.vayunmathur.maps.util.ParkingViewModel, transitViewModel: com.vayunmathur.maps.util.TransitStopsViewModel) {
    val selectedFeature by viewModel.selectedFeature.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val messenger = rememberMessenger()
    val noResultsMessage = stringResource(MapsR.string.no_results_found)

    val chrome = rememberMapChromeState()
    val camera = rememberCameraState(INITIAL_CAMERA)

    val selectedFeature by viewModel.selectedFeature.collectAsState()
    // Keep the router's plan order on the visible tab: routes plans the
    // selected mode first, so a tab switch made before the flow restarts must
    // still reach it. A plain assignment (not a flow input) so switching tabs
    // never re-plans every mode — the flow restarts on selection change only.
    val selectedRouteType = chrome.selectedRouteType
    LaunchedEffect(selectedRouteType) {
        viewModel.selectedRouteMode = selectedRouteType
    }
    val inactiveNavigation by viewModel.inactiveNavigation.collectAsState()
    val route by viewModel.routes.collectAsState(null)
    // Collected here (rather than inside the sheet) so the inner place sheet's
    // contentKey can name them: the tab switch and the late-arriving reviews
    // each change the sheet's content height, and without them in the key the
    // learned ceiling from Details traps the taller Reviews list.
    val poiSection by viewModel.poiSection.collectAsState()
    val currentPoiInfo by viewModel.currentPoiInfo.collectAsState()

    // Parking memory (P9): the single active parking spot (pin + recall).
    val parkingSpot by parkingViewModel.active.collectAsState()
    var showParkingSheet by remember { mutableStateOf(false) }

    // Map-layer visibility toggles (P6 layers sheet, persisted via DataStore).
    val trafficEnabled by settingsViewModel.trafficLayer.collectAsState()
    val satelliteEnabled by settingsViewModel.satelliteLayer.collectAsState()
    val safetyEnabled by settingsViewModel.safetyLayer.collectAsState()
    val transitEnabled by settingsViewModel.transitLayer.collectAsState()
    var showLayersSheet by remember { mutableStateOf(false) }

    // Effective map palette (P14): resolve the P6 Map-theme setting against the
    // OS dark mode using the same rule DynamicTheme applies (a null override =
    // follow the system). Drives the runtime dark recolor of the basemap below,
    // so the map flips light<->dark together with the rest of the app chrome.
    val themeMode by settingsViewModel.themeMode.collectAsState()
    val darkMap = themeMode.darkOverride ?: isSystemInDarkTheme()

    // Public transit (P10): nearby stops overlay + live departure board. Stops
    // are only fetched/drawn while the Transit layer is on.
    val transitStops by transitViewModel.stops.collectAsState()
    val selectedTransitStop by transitViewModel.selected.collectAsState()
    val departuresState by transitViewModel.departures.collectAsState()

    // Google POI overlay pins (viewport scrape → custom layer, replacing the
    // suppressed native basemap POIs).
    val googlePins by poiViewModel.pins.collectAsState()

    // Search-result pins (from the Google search page) drawn on the map.
    val searchResults by searchViewModel.results.collectAsState()

    // Live family-location pins (P18): the findfamily bound service is bound
    // while this screen is composed (see rememberFamilyMembers' DisposableEffect)
    // and pushes updates only while bound. Empty when findfamily is absent.
    val familyMembers by com.vayunmathur.maps.ipc.rememberFamilyMembers()

    val camera = rememberCameraState(CameraPosition(target = Position(-118.243683,34.052235), zoom = 5.0))

    LaunchedEffect(camera.position, transitEnabled) {
        if (camera.position.zoom >= 11.0) {
            delay(300) // Debounce traffic loading
            val projection = camera.projection
            if (projection != null) {
                val bbox = projection.queryVisibleBoundingBox()
                // Only fetch live traffic when the layer is on. Fetching drives
                // trafficVersion, which in turn mounts the loopback traffic tile
                // layer (see MyMapLayers); skipping it when off avoids needless
                // network + native work and keeps the tile layer unmounted.
                if (trafficEnabled) {
                    // Load traffic for all four corners to ensure the current view is covered
                    OfflineRouter.ensureTrafficLoadedNative(bbox.north, bbox.east, true)
                    OfflineRouter.ensureTrafficLoadedNative(bbox.north, bbox.west, true)
                    OfflineRouter.ensureTrafficLoadedNative(bbox.south, bbox.east, true)
                    OfflineRouter.ensureTrafficLoadedNative(bbox.south, bbox.west, true)
                }
                // Refresh the Google POI overlay for the idle viewport (VM
                // debounces + LRU-caches the keyless scrape).
                poiViewModel.onViewport(bbox.north, bbox.east, bbox.south, bbox.west)
                // Refresh nearby transit stops (P10) only while the layer is on
                // (VM debounces + caches; wide views clear the overlay).
                if (transitEnabled) {
                    transitViewModel.onViewport(bbox.north, bbox.east, bbox.south, bbox.west)
                }
            }
        }
    }

    // Online-tiles-only: the basemap always streams live (offline zone tile
    // packs were removed). Offline ROUTING still works via the downloaded graph.
    val hybridUrl = MapTileCache.BASEMAP_PMTILES_URL

    // Inside MapPage
    var json by remember { mutableStateOf<String?>(null) }

    // Read the style asset on Dispatchers.IO (file open), then the hybrid
    // patch step is light enough to stay on the same coroutine. Re-runs on a
    // theme change (darkMap) so the recolored style reloads and flips live.
    LaunchedEffect(hybridUrl, darkMap) {
        val updatedStyle = withContext<String>(Dispatchers.IO) {
            val rawStyle = context.assets.open("style.json").bufferedReader().readText()
            patchStyleForHybrid(
                rawStyle,
                MapTileCache.BASEMAP_PMTILES_URL,
                hybridUrl,
                darkMap,
            )
        }
        Log.d(
            "MapPage",
            "style patched base=${MapTileCache.BASEMAP_PMTILES_URL} hybrid=$hybridUrl " +
                "darkMap=$darkMap jsonLen=${updatedStyle.length}",
        )
        json = updatedStyle
    }

    // --- LOCATION & OSM INITIALIZATION ---
    val userPosition by viewModel.userPosition.collectAsState()
    val userBearing by viewModel.userBearing.collectAsState()
    val userHeadingAccuracy by viewModel.userHeadingAccuracy.collectAsState()

    val savedList by savedPlacesViewModel.saved.collectAsState()
    // The starred list drawn as one pin set, deduped.
    val savedPins = remember(savedList) { savedList.distinct() }

    val parkingSpot by parkingViewModel.active.collectAsState()
    val searchResults by searchViewModel.results.collectAsState()
    val searchQuery by searchViewModel.query.collectAsState()
    val searchRecents by searchViewModel.recents.collectAsState()
    val searching by searchViewModel.searching.collectAsState()
    val selectedTransitStop by transitViewModel.selected.collectAsState()
    val departuresState by transitViewModel.departures.collectAsState()
    val tripItineraryState by transitViewModel.tripItinerary.collectAsState()

    // The findfamily service is bound only while this screen is composed, so this is empty when
    // findfamily is absent.
    val familyMembers by com.vayunmathur.maps.ipc.rememberFamilyMembers()

    val trafficEnabled by settingsViewModel.trafficLayer.collectAsState()
    val satelliteEnabled by settingsViewModel.satelliteLayer.collectAsState()
    val safetyEnabled by settingsViewModel.safetyLayer.collectAsState()
    val transitEnabled by settingsViewModel.transitLayer.collectAsState()
    val globeEnabled by settingsViewModel.globeEnabled.collectAsState()

    // The Moon raster pair, loaded lazily on first Moon select (never for Earth
    // sessions): 37MB of assets read once, cached for the session. `body` lives
    // on the chrome (session-only); the textures ride the scope to the surface.
    var moonTextures by remember { mutableStateOf<MoonTextures?>(null) }
    LaunchedEffect(chrome.body) {
        if (chrome.body == MapBody.Moon && moonTextures == null) {
            moonTextures = MoonAssetLoader.load(context)
        }
    }

    // Resolve the P6 map-theme setting against the OS the same way DynamicTheme does, so the map
    // flips light/dark together with the rest of the chrome.
    val themeMode by settingsViewModel.themeMode.collectAsState()
    val darkMap = themeMode.darkOverride ?: isSystemInDarkTheme()

    val navState by NavigationSessionManager.state.collectAsState()
    // Collected, not read off a field: a recalculation swaps the route mid-session, and reading
    // it as a plain property meant this screen kept drawing the OLD route's steps against the
    // new progress until something else happened to recompose.
    val navSession by NavigationSessionManager.session.collectAsState()
    val isNavigating = navState !is NavigationSessionManager.NavState.Idle
    val navProgress = (navState as? NavigationSessionManager.NavState.Navigating)?.progress

    // The side panel takes this content in wide mode, so the sheets stay
    // hidden there and the effects below only drive them in compact. A suspend
    // on an unmeasured scaffold would hang, not no-op, so every effect that
    // touches one is keyed on `wide` and returns early when it is true.
    val wide = isExpandedWidth()
    val sheetState = rememberFreeHeightSheetState(SheetValue.Hidden)
    // A second, independent sheet stacked over the first, rather than a mode of it. The two have
    // different peek heights and different lifetimes, and driving one state through both would
    // mean re-measuring the sheet at the moment its content swaps — which is exactly when the
    // learned content ceiling and the peek floor disagree.
    val searchSheetState = rememberFreeHeightSheetState(SheetValue.Hidden)
    // Null in wide mode — the side panel takes the sheets' content, so nothing
    // may suspend on them (an unmeasured scaffold's hide()/partialExpand hangs
    // rather than no-ops). The effects below and MapContentBox read these, and
    // the compact scaffolds below take the non-null originals directly.
    val wideSheetState: FreeHeightSheetState? =
        if (wide) null else sheetState
    val wideSearchSheetState: FreeHeightSheetState? =
        if (wide) null else searchSheetState
    // How much of the bottom the collapsed search bar is occupying, so the FAB stack and the
    // scale bar can clear it the same way they clear a sheet. Zero whenever the bar is not drawn.
    var searchBarLiftPx by remember { mutableIntStateOf(0) }
    val searchHost = rememberMapSearchHost(searchViewModel, camera)
    val searchRequest = searchHost.searchRequest
    val searchState = SearchUiState(
        query = searchQuery,
        results = searchResults,
        recents = searchRecents,
        searching = searching,
    )
    val browsing = selectedFeature == null && inactiveNavigation == null && !isNavigating
    // The bar is chrome, not a sheet, so it is composed away rather than animated out. Its
    // measured height therefore has to be discounted explicitly — a stale one would leave the
    // FAB stack floating above a bar that is no longer there.
    val searchBarVisible = browsing && searchRequest == null
    // Expanded widths show this content in the side panel instead of a sheet.
    val panelSearch = wide && searchRequest != null
    val panelPlace = wide && searchRequest == null &&
        (selectedFeature != null || route != null || inactiveNavigation != null)
    fun openSearch(query: String? = null, waypointIndex: Int? = null) = searchHost.openSearch(query, waypointIndex)
    val settingsAction = rememberSettingsAction(backStack)
    MapSheetEffects(
        wide = wide,
        viewModel = viewModel,
        backStack = backStack,
        camera = camera,
        chrome = chrome,
        selectedFeature = selectedFeature,
        inactiveNavigation = inactiveNavigation,
        navProgress = navProgress,
        isNavigating = isNavigating,
        coroutineScope = coroutineScope,
        sheetState = wideSheetState,
        searchSheetState = wideSearchSheetState,
        searchHost = searchHost,
    )
    NavigationCameraFollow(camera, chrome, navProgress, isNavigating)

    val searchActions = rememberSearchActions(
        searchRequest = searchRequest,
        searchViewModel = searchViewModel,
        viewModel = viewModel,
        coroutineScope = coroutineScope,
        onCloseSearch = { searchHost.searchRequest = null },
    )

    val scope = MapPageScope(
        context = context,
        backStack = backStack,
        viewModel = viewModel,
        savedPlacesViewModel = savedPlacesViewModel,
        searchViewModel = searchViewModel,
        settingsViewModel = settingsViewModel,
        parkingViewModel = parkingViewModel,
        transitViewModel = transitViewModel,
        coroutineScope = coroutineScope,
        messenger = messenger,
        noResultsMessage = noResultsMessage,
        chrome = chrome,
        camera = camera,
        selectedFeature = selectedFeature,
        inactiveNavigation = inactiveNavigation,
        route = route,
        poiSection = poiSection,
        poiEnrichmentKey = PoiEnrichmentKey.of(currentPoiInfo),
        userPosition = userPosition,
        userBearing = userBearing,
        userHeadingAccuracy = userHeadingAccuracy,
        savedPins = savedPins,
        parkingSpot = parkingSpot,
        searchResults = searchResults,
        searchRecents = searchRecents,
        selectedTransitStop = selectedTransitStop,
        departuresState = departuresState,
        tripItineraryState = tripItineraryState,
        familyMembers = familyMembers,
        trafficEnabled = trafficEnabled,
        satelliteEnabled = satelliteEnabled,
        safetyEnabled = safetyEnabled,
        transitEnabled = transitEnabled,
        globeEnabled = globeEnabled,
        body = chrome.body,
        moonTextures = moonTextures,
        darkMap = darkMap,
        navState = navState,
        navSession = navSession,
        navProgress = navProgress,
        isNavigating = isNavigating,
        browsing = browsing,
        searchBarVisible = searchBarVisible,
        searchBarLiftPx = searchBarLiftPx,
        onSearchBarLift = { searchBarLiftPx = it },
        sheetState = if (wide) null else sheetState,
        searchSheetState = if (wide) null else searchSheetState,
        searchRequest = searchRequest,
        openSearch = ::openSearch,
        settingsAction = settingsAction,
        wide = wide,
    )
    if (wide) {
        scope.MapWideHost(
            searchActions = searchActions,
            searchState = searchState,
            panelSearch = panelSearch,
            panelPlace = panelPlace,
        )
        return
    }
    scope.MapSheets(
        searchActions = searchActions,
        searchState = searchState,
    )
}

/** End the session and stop the foreground service that outlives this screen. */
internal fun stopNavigation(context: android.content.Context) {
    NavigationSessionManager.stop()
    context.stopService(
        android.content.Intent(context, com.vayunmathur.maps.util.NavigationService::class.java)
    )
// Native basemap layers suppressed at runtime — amenities are Google-only now
// (custom overlay layer). Keeping this in code (vs editing style.json) makes it
// OTA-swappable per Decision D1.
private val SUPPRESSED_LAYERS = setOf("pois")

fun patchStyleForHybrid(
    jsonString: String,
    baseLocalUrl: String,
    hybridUrl: String,
    dark: Boolean = false,
): String {
    val json = Json { ignoreUnknownKeys = true }
    val root = json.parseToJsonElement(jsonString).jsonObject

    val newSources = buildJsonObject {
        putJsonObject("protomaps_base") {
            put("type", "vector")
            put("url", baseLocalUrl)
        }
        putJsonObject("protomaps_hybrid") {
            put("type", "vector")
            put("url", hybridUrl)
        }
    }

    val oldLayers = root["layers"]?.jsonArray ?: buildJsonArray {}
    val newLayers = buildJsonArray {
        oldLayers.forEach { layerElement ->
            val layer = layerElement.jsonObject
            val id = layer["id"]?.jsonPrimitive?.content ?: ""
            val type = layer["type"]?.jsonPrimitive?.content ?: ""

            // Suppress native basemap POIs at runtime (Decision D1) — amenities
            // are Google-only now, rendered on the custom overlay layer. Dropping
            // the source layer here (rather than editing style.json) keeps it
            // OTA-swappable. Also drops the would-be _base/_hybrid variants.
            if (id in SUPPRESSED_LAYERS) return@forEach

            // Dark palette (P14): recolor the base Protomaps paint at runtime so
            // we don't duplicate the 3544-line style.json. Only colour keys are
            // swapped; width/opacity/dasharray expressions are preserved.
            val darkPaint = if (dark) darkenPaint(id, layer["paint"] as? JsonObject) else null

            if (type == "background") {
                add(buildJsonObject {
                    layer.forEach { (k, v) -> if (!(dark && k == "paint")) put(k, v) }
                    if (darkPaint != null) put("paint", darkPaint)
                })
            } else {
                // Zoom 0-7: Base Local
                add(buildJsonObject {
                    layer.forEach { (k, v) -> if (!(dark && k == "paint")) put(k, v) }
                    if (darkPaint != null) put("paint", darkPaint)
                    put("id", "${id}_base")
                    put("source", "protomaps_base")
                    put("maxzoom", 7)
                })
                // Zoom 7+: Hybrid (Local Only)
                add(buildJsonObject {
                    layer.forEach { (k, v) -> if (!(dark && k == "paint")) put(k, v) }
                    if (darkPaint != null) put("paint", darkPaint)
                    put("id", "${id}_hybrid")
                    put("source", "protomaps_hybrid")
                    put("minzoom", 7)
                })
            }
        }
    }

    return buildJsonObject {
        root.forEach { (k, v) -> if (k != "sources" && k != "layers") put(k, v) }
        put("sources", newSources)
        put("layers", newLayers)
    }.toString()
}

/**
 * Rebuild a layer's `paint` for the dark palette (P14): copy every property
 * verbatim and only swap the colour keys, so zoom-driven width/opacity/dasharray
 * expressions keep working. `text-halo-width` etc. are left untouched. Layers
 * without a colour key (e.g. the icon-only `roads_oneway`) come back unchanged.
 */
private fun darkenPaint(id: String, paint: JsonObject?): JsonObject? {
    if (paint == null) return null
    val base = darkBaseColor(id)
    val (text, halo) = darkTextColors(id)
    return buildJsonObject {
        paint.forEach { (k, v) ->
            when (k) {
                "background-color", "fill-color", "line-color" -> put(k, base)
                "text-color" -> put(k, text)
                "text-halo-color" -> put(k, halo)
                else -> put(k, v)
            }
        }
    }
}

/**
 * Dark fill/line/background colour for a base Protomaps layer. Keyed by layer id
 * (the style's ids are stable), falling through prefix/substring rules for the
 * many road variants. Casing colours must be matched before the highway/major
 * rules because e.g. `roads_highway_casing_early` contains both tokens.
 */
private fun darkBaseColor(id: String): String = when {
    id == "background" || id == "earth" -> "#1b1d22"
    id == "water" -> "#0d1b2a"
    id == "water_stream" || id == "water_river" -> "#24455f"
    id == "landcover" -> "#1f2a22"
    id == "landuse_park" -> "#1e2b20"
    id == "landuse_urban_green" -> "#23362a"
    id == "landuse_hospital" -> "#2b2528"
    id == "landuse_industrial" -> "#20262b"
    id == "landuse_school" -> "#282520"
    id == "landuse_beach" -> "#2c2a22"
    id == "landuse_zoo" -> "#213030"
    id == "landuse_aerodrome" -> "#212228"
    id == "landuse_runway" -> "#2b2d33"
    id == "landuse_pedestrian" -> "#242229"
    id == "landuse_pier" -> "#202225"
    id.startsWith("landuse") -> "#1f2126"
    id == "buildings" -> "#22262c"
    id.startsWith("boundaries") -> "#4a4f57"
    id == "roads_rail" -> "#3a3e45"
    id.startsWith("roads_runway") || id.startsWith("roads_taxiway") -> "#2b2d33"
    id.contains("casing") -> "#111318"
    id.startsWith("roads_tunnels") -> "#2b2e35"
    id.contains("highway") || id.contains("major") || id.contains("link") -> "#464b54"
    id.startsWith("roads") -> "#34383f"
    else -> "#26282e"
}

/**
 * Dark (text-color, text-halo-color) for a label/POI symbol layer: light text on
 * a near-black halo so labels stay legible over the dark basemap. Water labels
 * keep a blue tint over the dark-navy water.
 */
private fun darkTextColors(id: String): Pair<String, String> = when (id) {
    "places_locality" -> "#e4e8ee" to "#101216"
    "places_country" -> "#9aa0aa" to "#101216"
    "places_region" -> "#80868f" to "#101216"
    "places_subplace" -> "#b0b6c0" to "#101216"
    "earth_label_islands" -> "#9aa0aa" to "#101216"
    "water_waterway_label", "water_label_ocean", "water_label_lakes" -> "#6f8fce" to "#0d1b2a"
    "roads_shields" -> "#c8ccd4" to "#101216"
    "roads_labels_major", "roads_labels_minor", "address_label" -> "#b8bdc6" to "#101216"
    else -> "#c9ced6" to "#101216"
}
