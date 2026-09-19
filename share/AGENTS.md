# AGENTS.md — share/ (':share')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `share/` + allowed shared modules. Do not root-scan.

_Install: ./install share (dev by default)._

- Gradle: :share / dir: share/
- Package roots present: ui, domain, platform, network
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: 
- Metadata: metadata_data/share.md (present)
- Rust: yes / screenshotTest: yes

## Key files

- com/vayunmathur/share/MainActivity.kt
- com/vayunmathur/share/Navigation.kt
- com/vayunmathur/share/Route.kt
- com/vayunmathur/share/SharePopupActivity.kt
- com/vayunmathur/share/domain/protocol/ShareSession.kt
- com/vayunmathur/share/network/transport/TcpTransport.kt
- com/vayunmathur/share/platform/ReceivedFileStore.kt
- com/vayunmathur/share/platform/SharePermissions.kt
- com/vayunmathur/share/platform/ShareUiContract.kt
- com/vayunmathur/share/platform/ShareViewModel.kt
- com/vayunmathur/share/platform/discovery/BleDiscoveryManager.kt
- com/vayunmathur/share/platform/discovery/NearbyDevice.kt
- com/vayunmathur/share/platform/discovery/NsdDiscoveryManager.kt
- com/vayunmathur/share/platform/receive/PostGate.kt
- com/vayunmathur/share/platform/receive/ShareBootReceiver.kt
- com/vayunmathur/share/platform/receive/ShareNotificationReceiver.kt
- com/vayunmathur/share/platform/receive/ShareReceiveController.kt
- com/vayunmathur/share/platform/receive/ShareReceiveNotifier.kt
- com/vayunmathur/share/platform/receive/ShareReceiveTileService.kt
- com/vayunmathur/share/platform/receive/ShareSaveActivity.kt
- com/vayunmathur/share/platform/transfer/ShareTransferService.kt
- com/vayunmathur/share/protocol/ShareNative.kt
- com/vayunmathur/share/ui/SharePopupSheet.kt
- com/vayunmathur/share/ui/ShareSendScreen.kt

## Verify (this module only)
```
./gradlew :share:compileDevKotlin
./gradlew :share:lint
./gradlew :share:checkMetadata
```


