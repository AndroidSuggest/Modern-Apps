# AGENTS.md — cast/ (':cast')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `cast/` + allowed shared modules. Do not root-scan.

_Install: ./install cast (dev by default)._

- Gradle: :cast / dir: cast/
- Package roots present: ui, domain, platform, network, service
- Entry files: MainActivity.kt, Route.kt, Navigation.kt, CastApplication.kt
- Deps: :library:remotedisplay-stubs, :cast:protocol, :sdk:cast
- Metadata: metadata_data/cast.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/cast/CastApplication.kt
- com/vayunmathur/cast/MainActivity.kt
- com/vayunmathur/cast/Navigation.kt
- com/vayunmathur/cast/Route.kt
- com/vayunmathur/cast/domain/CastDevice.kt
- com/vayunmathur/cast/domain/ClientState.kt
- com/vayunmathur/cast/network/CastUdpTransport.kt
- com/vayunmathur/cast/network/ControlSocket.kt
- com/vayunmathur/cast/platform/CastCodecNegotiation.kt
- com/vayunmathur/cast/platform/CastConnection.kt
- com/vayunmathur/cast/platform/CastContentSession.kt
- com/vayunmathur/cast/platform/CastController.kt
- com/vayunmathur/cast/platform/CastDesktopDisplay.kt
- com/vayunmathur/cast/platform/CastMirrorSession.kt
- com/vayunmathur/cast/platform/CastPairActivity.kt
- com/vayunmathur/cast/platform/CastPickerActivity.kt
- com/vayunmathur/cast/platform/CastSessionTypes.kt
- com/vayunmathur/cast/platform/CastSessionWatch.kt
- com/vayunmathur/cast/platform/CastUiContract.kt
- com/vayunmathur/cast/platform/CastViewModel.kt
- com/vayunmathur/cast/platform/MediaProxyServer.kt
- com/vayunmathur/cast/platform/MirrorClient.kt
- com/vayunmathur/cast/platform/discovery/CastDiscoveryManager.kt
- com/vayunmathur/cast/platform/mirror/AudioEncoder.kt
- com/vayunmathur/cast/platform/mirror/EncoderSupport.kt
- com/vayunmathur/cast/platform/mirror/MirrorConsentActivity.kt
- com/vayunmathur/cast/platform/mirror/MirrorEngine.kt
- com/vayunmathur/cast/platform/mirror/MirrorGeometry.kt
- com/vayunmathur/cast/platform/mirror/MirrorPreferences.kt
- com/vayunmathur/cast/platform/mirror/MirrorSource.kt
- com/vayunmathur/cast/platform/mirror/MirrorTileService.kt
- com/vayunmathur/cast/platform/mirror/OpusEncoder.kt
- com/vayunmathur/cast/platform/mirror/PcmAudioEncoder.kt
- com/vayunmathur/cast/platform/mirror/ScreenCapture.kt
- com/vayunmathur/cast/platform/mirror/StreamSender.kt
- com/vayunmathur/cast/platform/mirror/VideoEncoder.kt
- com/vayunmathur/cast/platform/remotedisplay/CastSystemDisplay.kt
- com/vayunmathur/cast/platform/remotedisplay/MaRemoteDisplayProvider.kt
- com/vayunmathur/cast/service/CastRemoteDisplayService.kt
- com/vayunmathur/cast/service/CastService.kt
- com/vayunmathur/cast/service/ClientResourceResolver.kt
- com/vayunmathur/cast/service/ContentCastService.kt
- com/vayunmathur/cast/ui/CastContent.kt
- com/vayunmathur/cast/ui/CastDeviceRow.kt
- com/vayunmathur/cast/ui/CastMirrorStatusCard.kt
- com/vayunmathur/cast/ui/CastPairCodeCard.kt
- com/vayunmathur/cast/ui/CastPairDialog.kt
- com/vayunmathur/cast/ui/CastPickerContent.kt
- com/vayunmathur/cast/ui/CastScreen.kt

## Verify (this module only)
```
./gradlew :cast:compileDevKotlin
./gradlew :cast:lint
./gradlew :cast:checkMetadata
```


