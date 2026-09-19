# AGENTS.md — astronomy/ (':astronomy')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `astronomy/` + allowed shared modules. Do not root-scan.

_Install: ./install astronomy (dev by default)._

- Gradle: :astronomy / dir: astronomy/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: 
- Metadata: metadata_data/astronomy.md (present)
- Rust: yes / screenshotTest: yes

## Key files

- com/vayunmathur/astronomy/MainActivity.kt
- com/vayunmathur/astronomy/Navigation.kt
- com/vayunmathur/astronomy/Route.kt
- com/vayunmathur/astronomy/data/CatalogRepository.kt
- com/vayunmathur/astronomy/data/model/Models.kt
- com/vayunmathur/astronomy/domain/AstronomyNative.kt
- com/vayunmathur/astronomy/domain/engine/CoordinateTransforms.kt
- com/vayunmathur/astronomy/domain/engine/LunarCalculator.kt
- com/vayunmathur/astronomy/domain/engine/PlanetaryCalculator.kt
- com/vayunmathur/astronomy/domain/engine/RiseSetCalculator.kt
- com/vayunmathur/astronomy/domain/engine/SolarCalculator.kt
- com/vayunmathur/astronomy/domain/engine/TimeEngine.kt
- com/vayunmathur/astronomy/domain/projection/SkyProjection.kt
- com/vayunmathur/astronomy/domain/projection/StereographicProjection.kt
- com/vayunmathur/astronomy/domain/projection/ViewState.kt
- com/vayunmathur/astronomy/platform/AstronomyUiContract.kt
- com/vayunmathur/astronomy/platform/AstronomyViewModel.kt
- com/vayunmathur/astronomy/platform/sensor/LocationProvider.kt
- com/vayunmathur/astronomy/ui/ObjectDetailPage.kt
- com/vayunmathur/astronomy/ui/SearchPage.kt
- com/vayunmathur/astronomy/ui/SettingsPage.kt
- com/vayunmathur/astronomy/ui/SkyArt.kt
- com/vayunmathur/astronomy/ui/SkyCanvas.kt
- com/vayunmathur/astronomy/ui/SkyGrid.kt
- com/vayunmathur/astronomy/ui/SkyMapPage.kt
- com/vayunmathur/astronomy/ui/components/CameraBackground.kt

## Verify (this module only)
```
./gradlew :astronomy:compileDevKotlin
./gradlew :astronomy:lint
./gradlew :astronomy:checkMetadata
```


