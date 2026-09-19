# AGENTS.md — findfamily/ (':findfamily')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `findfamily/` + allowed shared modules. Do not root-scan.

_Install: ./install findfamily (dev by default)._

- Gradle: :findfamily / dir: findfamily/
- Package roots present: ui, data, domain, platform, intents, service
- Entry files: MainActivity.kt
- Deps: :library:network, :library:e2ee-p2p, :library:room, :library:work, :library:image, :library:map, :library:nearby-stubs
- Metadata: metadata_data/findfamily.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/findfamily/MainActivity.kt
- com/vayunmathur/findfamily/data/Coord.kt
- com/vayunmathur/findfamily/data/DirectBootStore.kt
- com/vayunmathur/findfamily/data/FFDatabase.kt
- com/vayunmathur/findfamily/data/FindFamilyRepository.kt
- com/vayunmathur/findfamily/data/LocationValue.kt
- com/vayunmathur/findfamily/data/NoShowAlert.kt
- com/vayunmathur/findfamily/data/NoShowAlertDao.kt
- com/vayunmathur/findfamily/data/TemporaryLink.kt
- com/vayunmathur/findfamily/data/User.kt
- com/vayunmathur/findfamily/data/Waypoint.kt
- com/vayunmathur/findfamily/domain/NoShowPolicy.kt
- com/vayunmathur/findfamily/intents/CreateLinkIntent.kt
- com/vayunmathur/findfamily/intents/GetIntent.kt
- com/vayunmathur/findfamily/ipc/FamilyLocationProtocol.kt
- com/vayunmathur/findfamily/ipc/FamilyLocationService.kt
- com/vayunmathur/findfamily/platform/FinalLocationReporter.kt
- com/vayunmathur/findfamily/platform/NoShowCheckScheduler.kt
- com/vayunmathur/findfamily/platform/PoweredOffBeacon.kt
- com/vayunmathur/findfamily/service/SharingTileService.kt
- com/vayunmathur/findfamily/tracker/PoweredOffBle.kt
- com/vayunmathur/findfamily/tracker/PoweredOffKeyStore.kt
- com/vayunmathur/findfamily/tracker/PoweredOffProtocol.kt
- com/vayunmathur/findfamily/tracker/PoweredOffRecovery.kt
- com/vayunmathur/findfamily/tracker/PoweredOffReporting.kt
- com/vayunmathur/findfamily/tracker/PoweredOffScanner.kt
- com/vayunmathur/findfamily/tracker/TrackerBeaconScanner.kt
- com/vayunmathur/findfamily/tracker/TrackerBinder.kt
- com/vayunmathur/findfamily/tracker/TrackerBle.kt
- com/vayunmathur/findfamily/tracker/TrackerFeature.kt
- com/vayunmathur/findfamily/tracker/TrackerProtocol.kt
- com/vayunmathur/findfamily/tracker/TrackerProvisioner.kt
- com/vayunmathur/findfamily/tracker/TrackerReporting.kt
- com/vayunmathur/findfamily/tracker/TrackerStore.kt
- com/vayunmathur/findfamily/tracker/TrackerUwbGatt.kt
- com/vayunmathur/findfamily/tracker/TrackerUwbKeys.kt
- com/vayunmathur/findfamily/ui/AutoToggleRow.kt
- com/vayunmathur/findfamily/ui/FamilyListSheet.kt
- com/vayunmathur/findfamily/ui/FindFamilyWideLayout.kt
- com/vayunmathur/findfamily/ui/MainFabOverlay.kt
- com/vayunmathur/findfamily/ui/MainMapSlot.kt
- com/vayunmathur/findfamily/ui/MainPage.kt
- com/vayunmathur/findfamily/ui/MainPageActions.kt
- com/vayunmathur/findfamily/ui/MainPageCards.kt
- com/vayunmathur/findfamily/ui/MainPageContent.kt
- com/vayunmathur/findfamily/ui/MainPageEffects.kt
- com/vayunmathur/findfamily/ui/MainPageOverlays.kt
- com/vayunmathur/findfamily/ui/MainSheetContent.kt
- com/vayunmathur/findfamily/ui/MapView.kt
- com/vayunmathur/findfamily/ui/PersonDetailSheet.kt
- com/vayunmathur/findfamily/ui/UserCard.kt
- com/vayunmathur/findfamily/ui/UwbRangingScreen.kt
- com/vayunmathur/findfamily/ui/dialogs/AddLinkDialog.kt
- com/vayunmathur/findfamily/ui/dialogs/AddPersonDialog.kt
- com/vayunmathur/findfamily/ui/dialogs/AddTrackerDialog.kt
- com/vayunmathur/findfamily/ui/dialogs/GpsFallbackWarningDialog.kt
- com/vayunmathur/findfamily/ui/dialogs/KeyMismatchDialog.kt
- com/vayunmathur/findfamily/ui/dialogs/NoShowAlertDialog.kt
- com/vayunmathur/findfamily/ui/dialogs/OutdatedPeerDialog.kt
- com/vayunmathur/findfamily/ui/dialogs/SecurityCodeDialog.kt
- com/vayunmathur/findfamily/util/AppBackupAgent.kt
- com/vayunmathur/findfamily/util/BootReceiver.kt
- com/vayunmathur/findfamily/util/FindFamilyNotificationChannels.kt
- com/vayunmathur/findfamily/util/FindFamilyUiContract.kt
- com/vayunmathur/findfamily/util/FindFamilyViewModel.kt
- com/vayunmathur/findfamily/util/LocationProviderStatus.kt
- com/vayunmathur/findfamily/util/LocationTrackingCrowdFinding.kt
- com/vayunmathur/findfamily/util/LocationTrackingDirectBoot.kt
- com/vayunmathur/findfamily/util/LocationTrackingHeartbeat.kt
- com/vayunmathur/findfamily/util/LocationTrackingInbound.kt
- com/vayunmathur/findfamily/util/LocationTrackingNotifications.kt
- com/vayunmathur/findfamily/util/LocationTrackingService.kt
- com/vayunmathur/findfamily/util/Networking.kt
- com/vayunmathur/findfamily/util/NetworkingHelper.kt
- com/vayunmathur/findfamily/util/NetworkingTracker.kt
- com/vayunmathur/findfamily/util/Platform.kt
- com/vayunmathur/findfamily/util/TrackingTileService.kt
- com/vayunmathur/findfamily/util/UwbSessionManager.kt
- com/vayunmathur/findfamily/uwb/UwbAccessoryProtocol.kt
- com/vayunmathur/findfamily/uwb/UwbController.kt
- com/vayunmathur/findfamily/uwb/UwbEnvelope.kt
- com/vayunmathur/findfamily/uwb/UwbInbox.kt

## Verify (this module only)
```
./gradlew :findfamily:compileDevKotlin
./gradlew :findfamily:lint
./gradlew :findfamily:checkMetadata
```


