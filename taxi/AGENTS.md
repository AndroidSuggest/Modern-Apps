# AGENTS.md — taxi/ (':taxi')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `taxi/` + allowed shared modules. Do not root-scan.

_Install: ./install taxi (dev by default)._

- Gradle: :taxi / dir: taxi/
- Package roots present: ui, data, platform, network, intents, provider, notifications
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:network, :library:map, :library:image
- Metadata: metadata_data/taxi.md (present)
- Rust: no / screenshotTest: no

## Key files

- com/vayunmathur/taxi/MainActivity.kt
- com/vayunmathur/taxi/Navigation.kt
- com/vayunmathur/taxi/Route.kt
- com/vayunmathur/taxi/data/Models.kt
- com/vayunmathur/taxi/data/lyft/LyftTokenStore.kt
- com/vayunmathur/taxi/data/uber/UberSession.kt
- com/vayunmathur/taxi/intents/RideEstimateIntent.kt
- com/vayunmathur/taxi/ipc/DirectionsClient.kt
- com/vayunmathur/taxi/ipc/IntentLaunch.kt
- com/vayunmathur/taxi/ipc/RideHandoffContract.kt
- com/vayunmathur/taxi/network/lyft/LyftAuth.kt
- com/vayunmathur/taxi/network/lyft/LyftCardTokenizer.kt
- com/vayunmathur/taxi/network/lyft/LyftOffersParser.kt
- com/vayunmathur/taxi/network/lyft/LyftProto.kt
- com/vayunmathur/taxi/network/lyft/LyftProvider.kt
- com/vayunmathur/taxi/network/lyft/LyftRideParser.kt
- com/vayunmathur/taxi/network/uber/UberAuth.kt
- com/vayunmathur/taxi/network/uber/UberProvider.kt
- com/vayunmathur/taxi/network/uber/UberWebView.kt
- com/vayunmathur/taxi/notifications/RideLiveUpdate.kt
- com/vayunmathur/taxi/notifications/RideTrackingService.kt
- com/vayunmathur/taxi/platform/deeplink/RideDeepLinks.kt
- com/vayunmathur/taxi/platform/location/LocationProvider.kt
- com/vayunmathur/taxi/provider/QuoteRepository.kt
- com/vayunmathur/taxi/provider/RideProvider.kt
- com/vayunmathur/taxi/ui/AccountsScreen.kt
- com/vayunmathur/taxi/ui/AddCardDialog.kt
- com/vayunmathur/taxi/ui/CurrentRideScreen.kt
- com/vayunmathur/taxi/ui/LyftBooking.kt
- com/vayunmathur/taxi/ui/LyftSignInScreen.kt
- com/vayunmathur/taxi/ui/RideContent.kt
- com/vayunmathur/taxi/ui/RideInputs.kt
- com/vayunmathur/taxi/ui/RideResults.kt
- com/vayunmathur/taxi/ui/RideRoute.kt
- com/vayunmathur/taxi/ui/RideScreen.kt
- com/vayunmathur/taxi/ui/RideTrackingCards.kt
- com/vayunmathur/taxi/ui/RideTrackingMap.kt
- com/vayunmathur/taxi/ui/RideTrackingScreen.kt
- com/vayunmathur/taxi/ui/TaxiRideWideLayout.kt
- com/vayunmathur/taxi/ui/UberConnectScreen.kt
- com/vayunmathur/taxi/ui/UberSignInScreen.kt

## Verify (this module only)
```
./gradlew :taxi:compileDevKotlin
./gradlew :taxi:lint
./gradlew :taxi:checkMetadata
```


