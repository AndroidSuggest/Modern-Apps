# AGENTS.md — things/ (':things')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `things/` + allowed shared modules. Do not root-scan.

_Install: ./install things (dev by default)._

- Gradle: :things / dir: things/
- Package roots present: ui, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: 
- Metadata: metadata_data/things.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/things/MainActivity.kt
- com/vayunmathur/things/Navigation.kt
- com/vayunmathur/things/Route.kt
- com/vayunmathur/things/platform/BleManager.kt
- com/vayunmathur/things/platform/BodyComposition.kt
- com/vayunmathur/things/platform/DeviceController.kt
- com/vayunmathur/things/platform/DeviceService.kt
- com/vayunmathur/things/platform/HealthConnectHelper.kt
- com/vayunmathur/things/platform/ScaleBleConnection.kt
- com/vayunmathur/things/platform/ScaleBleManager.kt
- com/vayunmathur/things/platform/ScaleBleProtocol.kt
- com/vayunmathur/things/ui/DevicesPage.kt
- com/vayunmathur/things/ui/HomePage.kt
- com/vayunmathur/things/ui/PermissionsPage.kt
- com/vayunmathur/things/ui/PermissionsRationaleActivity.kt

## Verify (this module only)
```
./gradlew :things:compileDevKotlin
./gradlew :things:lint
./gradlew :things:checkMetadata
```


