# AGENTS.md — auto/ (':auto')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `auto/` + allowed shared modules. Do not root-scan.

_Install: ./install auto (dev by default)._

- Gradle: :auto / dir: auto/
- Package roots present: ui, platform, network, service, notifications, telephony
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :auto:protocol
- Metadata: metadata_data/auto.md (present)
- Rust: no / screenshotTest: no

## Key files

- com/vayunmathur/auto/MainActivity.kt
- com/vayunmathur/auto/Navigation.kt
- com/vayunmathur/auto/Route.kt
- com/vayunmathur/auto/network/HeadUnitServer.kt
- com/vayunmathur/auto/network/TransportIntake.kt
- com/vayunmathur/auto/network/UsbConnector.kt
- com/vayunmathur/auto/network/WifiDirectConnector.kt
- com/vayunmathur/auto/notifications/MessageMirrorBus.kt
- com/vayunmathur/auto/notifications/MessageMirrorService.kt
- com/vayunmathur/auto/notifications/MessageReplyRoute.kt
- com/vayunmathur/auto/platform/ActiveCallInfo.kt
- com/vayunmathur/auto/platform/AppDecorService.kt
- com/vayunmathur/auto/platform/AudioEvent.kt
- com/vayunmathur/auto/platform/AudioSinkChannel.kt
- com/vayunmathur/auto/platform/AudioState.kt
- com/vayunmathur/auto/platform/AutoConnectionState.kt
- com/vayunmathur/auto/platform/AutoSessionState.kt
- com/vayunmathur/auto/platform/AutoViewModel.kt
- com/vayunmathur/auto/platform/BootReceiver.kt
- com/vayunmathur/auto/platform/CallCardPush.kt
- com/vayunmathur/auto/platform/CarAppHost.kt
- com/vayunmathur/auto/platform/CarApps.kt
- com/vayunmathur/auto/platform/CarCallCard.kt
- com/vayunmathur/auto/platform/CarChimeraService.kt
- com/vayunmathur/auto/platform/CarCompanionDeviceService.kt
- com/vayunmathur/auto/platform/CarDisplay.kt
- com/vayunmathur/auto/platform/CarDrawerView.kt
- com/vayunmathur/auto/platform/CarMediaCard.kt
- com/vayunmathur/auto/platform/CarNavCard.kt
- com/vayunmathur/auto/platform/CarPresentation.kt
- com/vayunmathur/auto/platform/CarRailView.kt
- com/vayunmathur/auto/platform/CarSetupService.kt
- com/vayunmathur/auto/platform/CarStartupService.kt
- com/vayunmathur/auto/platform/CarSystemUiControllerService.kt
- com/vayunmathur/auto/platform/CarTts.kt
- com/vayunmathur/auto/platform/CarUiKit.kt
- com/vayunmathur/auto/platform/ConnectionResetReceiver.kt
- com/vayunmathur/auto/platform/ConnectivityEventReceiver.kt
- com/vayunmathur/auto/platform/DeepLinkResolverActivity.kt
- com/vayunmathur/auto/platform/GuidanceChannel.kt
- com/vayunmathur/auto/platform/InputChannel.kt
- com/vayunmathur/auto/platform/InputEvent.kt
- com/vayunmathur/auto/platform/MaosRoleStatus.kt
- com/vayunmathur/auto/platform/MediaNowPlaying.kt
- com/vayunmathur/auto/platform/MediaPlaybackMonitor.kt
- com/vayunmathur/auto/platform/Messaging.kt
- com/vayunmathur/auto/platform/MessagingAudio.kt
- com/vayunmathur/auto/platform/MessagingPrefs.kt
- com/vayunmathur/auto/platform/MicPermission.kt
- com/vayunmathur/auto/platform/MicSourceChannel.kt
- com/vayunmathur/auto/platform/MusicCapture.kt
- com/vayunmathur/auto/platform/MusicCapturePrefs.kt
- com/vayunmathur/auto/platform/MusicCaptureSinkHolder.kt
- com/vayunmathur/auto/platform/NavGuidanceMonitor.kt
- com/vayunmathur/auto/platform/NavStatusChannel.kt
- com/vayunmathur/auto/platform/PairingViewModel.kt
- com/vayunmathur/auto/platform/PhoneStatusMonitor.kt
- com/vayunmathur/auto/platform/SensorChannel.kt
- com/vayunmathur/auto/platform/SensorEvent.kt
- com/vayunmathur/auto/platform/TransportState.kt
- com/vayunmathur/auto/platform/UsbReceiver.kt
- com/vayunmathur/auto/platform/UsbTPlusReceiver.kt
- com/vayunmathur/auto/platform/VideoEncoder.kt
- com/vayunmathur/auto/platform/VideoSinkChannel.kt
- com/vayunmathur/auto/platform/WifiBluetoothReceiver.kt
- com/vayunmathur/auto/platform/WirelessSetupCarService.kt
- com/vayunmathur/auto/platform/WirelessSetupSharedService.kt
- com/vayunmathur/auto/platform/WirelessStartupActivity.kt
- com/vayunmathur/auto/platform/WirelessStartupReceiver.kt
- com/vayunmathur/auto/service/CarAppHostSession.kt
- com/vayunmathur/auto/service/CarMessagingAudio.kt
- com/vayunmathur/auto/service/MessagingCarAppService.kt
- com/vayunmathur/auto/service/MusicCaptureService.kt
- com/vayunmathur/auto/service/ProjectionService.kt
- com/vayunmathur/auto/service/UnownedServiceLog.kt
- com/vayunmathur/auto/telephony/CarProjectionInCallService.kt
- com/vayunmathur/auto/telephony/NonCarInCallService.kt
- com/vayunmathur/auto/ui/AudioRow.kt
- com/vayunmathur/auto/ui/AutoScreen.kt
- com/vayunmathur/auto/ui/MessagingConsentCard.kt
- com/vayunmathur/auto/ui/MicPermissionCard.kt
- com/vayunmathur/auto/ui/MusicCaptureCard.kt
- com/vayunmathur/auto/ui/MusicCaptureGrant.kt
- com/vayunmathur/auto/ui/PairingScreen.kt
- com/vayunmathur/auto/ui/SessionCard.kt

## Verify (this module only)
```
./gradlew :auto:compileDevKotlin
./gradlew :auto:lint
./gradlew :auto:checkMetadata
```


