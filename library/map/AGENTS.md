# AGENTS.md — library/map/ (':library:map')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/map/` + allowed shared modules. Do not root-scan.

- Gradle: :library:map / dir: library/map/
- Package roots present: 
- Entry files: 
- Deps: :library:network
- Metadata: metadata_data/library-map.md (MISSING)
- Rust: yes / screenshotTest: no

## Key files

- com/vayunmathur/library/map/CameraState.kt
- com/vayunmathur/library/map/FrameStatsOverlay.kt
- com/vayunmathur/library/map/GeoTypes.kt
- com/vayunmathur/library/map/ImageOverlay.kt
- com/vayunmathur/library/map/LayerOptions.kt
- com/vayunmathur/library/map/MapGestures.kt
- com/vayunmathur/library/map/MapMarker.kt
- com/vayunmathur/library/map/MapNative.kt
- com/vayunmathur/library/map/MapOptions.kt
- com/vayunmathur/library/map/MapRenderState.kt
- com/vayunmathur/library/map/MapScope.kt
- com/vayunmathur/library/map/MapStyle.kt
- com/vayunmathur/library/map/Mercator.kt
- com/vayunmathur/library/map/Projection.kt
- com/vayunmathur/library/map/RegionLevel.kt
- com/vayunmathur/library/map/RouteOverlay.kt
- com/vayunmathur/library/map/RouteStyle.kt
- com/vayunmathur/library/map/SurfaceMapOverlays.kt
- com/vayunmathur/library/map/SurfaceMapRenderer.kt
- com/vayunmathur/library/map/TrafficColorTable.kt
- com/vayunmathur/library/map/UserPuck.kt
- com/vayunmathur/library/map/VectorMap.kt
- com/vayunmathur/library/map/VulkanMapSurface.kt

## Verify (this module only)
```
./gradlew :library:map:compileDevKotlin
./gradlew :library:map:lint
```


