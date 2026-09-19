# AGENTS.md — measure/ (':measure')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `measure/` + allowed shared modules. Do not root-scan.

_Install: ./install measure (dev by default)._

- Gradle: :measure / dir: measure/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: 
- Metadata: metadata_data/measure.md (present)
- Rust: yes / screenshotTest: yes

## Key files

- com/vayunmathur/measure/MainActivity.kt
- com/vayunmathur/measure/Navigation.kt
- com/vayunmathur/measure/Route.kt
- com/vayunmathur/measure/data/model/Models.kt
- com/vayunmathur/measure/domain/HeldOrientation.kt
- com/vayunmathur/measure/domain/LevelMath.kt
- com/vayunmathur/measure/domain/MeasureNative.kt
- com/vayunmathur/measure/domain/Polygon.kt
- com/vayunmathur/measure/domain/Tilt.kt
- com/vayunmathur/measure/domain/Units.kt
- com/vayunmathur/measure/platform/CameraIntrinsics.kt
- com/vayunmathur/measure/platform/MeasureUiContract.kt
- com/vayunmathur/measure/platform/MeasureViewModel.kt
- com/vayunmathur/measure/platform/sensor/ImuRecorder.kt
- com/vayunmathur/measure/platform/sensor/TiltSensor.kt
- com/vayunmathur/measure/ui/ArMeasureContent.kt
- com/vayunmathur/measure/ui/ArMeasurePage.kt
- com/vayunmathur/measure/ui/CompassContent.kt
- com/vayunmathur/measure/ui/CompassPage.kt
- com/vayunmathur/measure/ui/DiagnosticsContent.kt
- com/vayunmathur/measure/ui/DiagnosticsPage.kt
- com/vayunmathur/measure/ui/LevelContent.kt
- com/vayunmathur/measure/ui/LevelPage.kt
- com/vayunmathur/measure/ui/RulerContent.kt
- com/vayunmathur/measure/ui/RulerPage.kt
- com/vayunmathur/measure/ui/SavedMeasurementsContent.kt
- com/vayunmathur/measure/ui/SavedMeasurementsPage.kt
- com/vayunmathur/measure/ui/SettingsContent.kt
- com/vayunmathur/measure/ui/SettingsPage.kt
- com/vayunmathur/measure/ui/components/MeasureBottomBar.kt
- com/vayunmathur/measure/ui/components/MeasureCamera.kt

## Verify (this module only)
```
./gradlew :measure:compileDevKotlin
./gradlew :measure:lint
./gradlew :measure:checkMetadata
```


