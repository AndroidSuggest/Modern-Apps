# AGENTS.md — cast/tv/ (':cast:tv')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `cast/tv/` + allowed shared modules. Do not root-scan.

_Install: ./install cast/tv (dev by default)._

- Gradle: :cast:tv / dir: cast/tv/
- Package roots present: ui, platform, service
- Entry files: MainActivity.kt
- Deps: :cast:protocol, :library:e2ee-p2p
- Metadata: metadata_data/cast-tv.md (present)
- Rust: no / screenshotTest: no

## Key files

- com/vayunmathur/cast/tv/MainActivity.kt
- com/vayunmathur/cast/tv/platform/ArtworkFetcher.kt
- com/vayunmathur/cast/tv/platform/AudioPlayer.kt
- com/vayunmathur/cast/tv/platform/ContentPlayer.kt
- com/vayunmathur/cast/tv/platform/ControlChannel.kt
- com/vayunmathur/cast/tv/platform/MediaReceiver.kt
- com/vayunmathur/cast/tv/platform/MirrorActivity.kt
- com/vayunmathur/cast/tv/platform/PairingStore.kt
- com/vayunmathur/cast/tv/platform/PanelModes.kt
- com/vayunmathur/cast/tv/platform/ReceiverAdvertiser.kt
- com/vayunmathur/cast/tv/platform/ReceiverContentSession.kt
- com/vayunmathur/cast/tv/platform/ReceiverController.kt
- com/vayunmathur/cast/tv/platform/ReceiverMediaLoop.kt
- com/vayunmathur/cast/tv/platform/ReceiverPairing.kt
- com/vayunmathur/cast/tv/platform/ReceiverUiContract.kt
- com/vayunmathur/cast/tv/platform/VideoDecoder.kt
- com/vayunmathur/cast/tv/service/ReceiverService.kt
- com/vayunmathur/cast/tv/ui/NowPlaying.kt
- com/vayunmathur/cast/tv/ui/ReceiverContent.kt
- com/vayunmathur/cast/tv/ui/Theme.kt
- com/vayunmathur/cast/tv/ui/TransportOverlay.kt

## Verify (this module only)
```
./gradlew :cast:tv:compileDevKotlin
./gradlew :cast:tv:lint
./gradlew :cast:tv:checkMetadata
```


