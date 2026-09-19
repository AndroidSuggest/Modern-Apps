# AGENTS.md — maps/ (':maps')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `maps/` + allowed shared modules. Do not root-scan.

_Install: ./install maps (dev by default)._

- Gradle: :maps / dir: maps/
- Package roots present: ui, data, intents
- Entry files: MainActivity.kt
- Deps: :library:map, :library:image, :library:network, :library:downloadservice
- Metadata: metadata_data/maps.md (present)
- Rust: yes / screenshotTest: yes

## Key files

- com/vayunmathur/maps/MainActivity.kt
- com/vayunmathur/maps/car/CarManeuvers.kt
- com/vayunmathur/maps/car/CarMapRenderer.kt
- com/vayunmathur/maps/car/CarSearchScreen.kt
- com/vayunmathur/maps/car/MapsCarAppService.kt
- com/vayunmathur/maps/car/MapsSession.kt
- com/vayunmathur/maps/car/NavMapScreen.kt
- com/vayunmathur/maps/data/GeoJson.kt
- com/vayunmathur/maps/data/MapLinkParser.kt
- com/vayunmathur/maps/data/MapPreferences.kt
- com/vayunmathur/maps/data/OpeningHours.kt
- com/vayunmathur/maps/data/OsmMaxspeed.kt
- com/vayunmathur/maps/data/ParkingStore.kt
- com/vayunmathur/maps/data/RecentSearchStore.kt
- com/vayunmathur/maps/data/SavedPlace.kt
- com/vayunmathur/maps/data/SavedPlaceStore.kt
- com/vayunmathur/maps/data/SpecificFeature.kt
- com/vayunmathur/maps/data/google/GooglePoiDataSource.kt
- com/vayunmathur/maps/data/google/GooglePoiDiscovery.kt
- com/vayunmathur/maps/data/google/GooglePoiMapModels.kt
- com/vayunmathur/maps/data/google/GooglePoiModels.kt
- com/vayunmathur/maps/data/google/GoogleResponse.kt
- com/vayunmathur/maps/data/google/GoogleSearchDataSource.kt
- com/vayunmathur/maps/data/google/GoogleTrafficSource.kt
- com/vayunmathur/maps/data/google/ReviewsWebParser.kt
- com/vayunmathur/maps/data/google/StreetViewDataSource.kt
- com/vayunmathur/maps/data/google/StreetViewModels.kt
- com/vayunmathur/maps/data/google/WebReviewsFetcher.kt
- com/vayunmathur/maps/data/transit/TransitousDataSource.kt
- com/vayunmathur/maps/data/transit/TransitousModels.kt
- com/vayunmathur/maps/intents/DirectionsIntent.kt
- com/vayunmathur/maps/ipc/FamilyLocationClient.kt
- com/vayunmathur/maps/ipc/FamilyLocationProtocol.kt
- com/vayunmathur/maps/ipc/IntentLaunch.kt
- com/vayunmathur/maps/ipc/OrderLookupClient.kt
- com/vayunmathur/maps/ipc/RideEstimateClient.kt
- com/vayunmathur/maps/ui/BottomSheetContent.kt
- com/vayunmathur/maps/ui/BottomSheetHeader.kt
- com/vayunmathur/maps/ui/CategoryChips.kt
- com/vayunmathur/maps/ui/CompassButton.kt
- com/vayunmathur/maps/ui/ContactAddressButton.kt
- com/vayunmathur/maps/ui/DeparturesSheet.kt
- com/vayunmathur/maps/ui/DepartureTime.kt
- com/vayunmathur/maps/ui/FamilyLocationLayer.kt
- com/vayunmathur/maps/ui/LayersButton.kt
- com/vayunmathur/maps/ui/LayersSheet.kt
- com/vayunmathur/maps/ui/LineBadge.kt
- com/vayunmathur/maps/ui/ManeuverIcon.kt
- com/vayunmathur/maps/ui/MapContentBox.kt
- com/vayunmathur/maps/ui/MapPage.kt
- com/vayunmathur/maps/ui/MapPageScope.kt
- com/vayunmathur/maps/ui/MapScaleBar.kt
- com/vayunmathur/maps/ui/MapSearchActions.kt
- com/vayunmathur/maps/ui/MapSheetEffects.kt
- com/vayunmathur/maps/ui/MapSheets.kt
- com/vayunmathur/maps/ui/MapSidePanel.kt
- com/vayunmathur/maps/ui/MapWideHost.kt
- com/vayunmathur/maps/ui/MapWideLayout.kt
- com/vayunmathur/maps/ui/NavigationOverlay.kt
- com/vayunmathur/maps/ui/ParkingLayer.kt
- com/vayunmathur/maps/ui/ParkingSheet.kt
- com/vayunmathur/maps/ui/PlaceActionRow.kt
- com/vayunmathur/maps/ui/PlaceSheet.kt
- com/vayunmathur/maps/ui/PlaceSheetHeader.kt
- com/vayunmathur/maps/ui/PlaceSheetHours.kt
- com/vayunmathur/maps/ui/PoiEnrichment.kt
- com/vayunmathur/maps/ui/RestaurantItem.kt
- com/vayunmathur/maps/ui/RoadsLayer.kt
- com/vayunmathur/maps/ui/RouteSheet.kt
- com/vayunmathur/maps/ui/RouteSheetTabs.kt
- com/vayunmathur/maps/ui/SafetyLayer.kt
- com/vayunmathur/maps/ui/SatelliteLayer.kt
- com/vayunmathur/maps/ui/SavedPlacesLayer.kt
- com/vayunmathur/maps/ui/SavedPlacesPage.kt
- com/vayunmathur/maps/ui/SearchResultLayer.kt
- com/vayunmathur/maps/ui/SearchSheet.kt
- com/vayunmathur/maps/ui/TransitStopsLayer.kt
- com/vayunmathur/maps/ui/TripSheet.kt
- com/vayunmathur/maps/ui/VoiceSearchButton.kt
- com/vayunmathur/maps/ui/map/MapCamera.kt
- com/vayunmathur/maps/ui/map/MapChrome.kt
- com/vayunmathur/maps/ui/map/MapChromeState.kt
- com/vayunmathur/maps/ui/map/MapFabStack.kt
- com/vayunmathur/maps/ui/map/MapFeaturePicker.kt
- com/vayunmathur/maps/ui/map/MapLayers.kt
- com/vayunmathur/maps/ui/map/MapOverlays.kt
- com/vayunmathur/maps/ui/map/MapPinFeatures.kt
- com/vayunmathur/maps/ui/map/MapSurface.kt
- com/vayunmathur/maps/ui/map/MapSurfaceHelpers.kt
- com/vayunmathur/maps/ui/map/RailLines.kt
- com/vayunmathur/maps/ui/map/RouteOverlayBuilder.kt
- com/vayunmathur/maps/ui/map/TrafficPrefetchEffect.kt
- com/vayunmathur/maps/ui/map/TransitVehicles.kt
- com/vayunmathur/maps/ui/map/WaypointList.kt
- com/vayunmathur/maps/ui/nav/ArrivalSummary.kt
- com/vayunmathur/maps/ui/nav/ElevationChart.kt
- com/vayunmathur/maps/ui/nav/LaneGuidance.kt
- com/vayunmathur/maps/ui/nav/ManeuverBanner.kt
- com/vayunmathur/maps/ui/nav/RouteShield.kt
- com/vayunmathur/maps/ui/nav/SpeedWidget.kt
- com/vayunmathur/maps/ui/nav/StepsSheet.kt
- com/vayunmathur/maps/ui/settings/MapSettingsPage.kt
- com/vayunmathur/maps/ui/streetview/StreetViewArrows.kt
- com/vayunmathur/maps/ui/streetview/StreetViewPegman.kt
- com/vayunmathur/maps/ui/streetview/StreetViewScreen.kt
- com/vayunmathur/maps/ui/theme/BasemapPalette.kt
- com/vayunmathur/maps/ui/theme/MapChromeMetrics.kt
- com/vayunmathur/maps/ui/theme/MapTokens.kt
- com/vayunmathur/maps/util/CameraBounds.kt
- com/vayunmathur/maps/util/ContactLookup.kt
- com/vayunmathur/maps/util/FrameworkLocationManager.kt
- com/vayunmathur/maps/util/GooglePoiMapViewModel.kt
- com/vayunmathur/maps/util/GTFSProvider.kt
- com/vayunmathur/maps/util/ItineraryWording.kt
- com/vayunmathur/maps/util/MapSettingsViewModel.kt
- com/vayunmathur/maps/util/MapsSearchViewModel.kt
- com/vayunmathur/maps/util/MapsUiContract.kt
- com/vayunmathur/maps/util/MapTileCache.kt
- com/vayunmathur/maps/util/NavigationService.kt
- com/vayunmathur/maps/util/NavigationSessionManager.kt
- com/vayunmathur/maps/util/NavigationTts.kt
- com/vayunmathur/maps/util/OfflineRouter.kt
- com/vayunmathur/maps/util/OfflineRouterRouteBuilder.kt
- com/vayunmathur/maps/util/OfflineRouterTraffic.kt
- com/vayunmathur/maps/util/OfflineRouterTransit.kt
- com/vayunmathur/maps/util/ParkingViewModel.kt
- com/vayunmathur/maps/util/PlacePanelState.kt
- com/vayunmathur/maps/util/PoiArchive.kt
- com/vayunmathur/maps/util/PoiCategories.kt
- com/vayunmathur/maps/util/PoiIndex.kt
- com/vayunmathur/maps/util/PoiIndexAttrs.kt
- com/vayunmathur/maps/util/PoiIndexSpatial.kt
- com/vayunmathur/maps/util/PoiIndexWords.kt
- com/vayunmathur/maps/util/PolylineProgress.kt
- com/vayunmathur/maps/util/RegionalUnits.kt
- com/vayunmathur/maps/util/RouteService.kt
- com/vayunmathur/maps/util/SavedPlacesViewModel.kt
- com/vayunmathur/maps/util/SelectedFeatureViewModel.kt
- com/vayunmathur/maps/util/SpatialKey.kt
- com/vayunmathur/maps/util/TransitClock.kt
- com/vayunmathur/maps/util/TransitStopsViewModel.kt
- com/vayunmathur/maps/util/Wikidata.kt

## Verify (this module only)
```
./gradlew :maps:compileDevKotlin
./gradlew :maps:lint
./gradlew :maps:checkMetadata
```


