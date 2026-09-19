# AGENTS.md — networklocation/ (':networklocation')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `networklocation/` + allowed shared modules. Do not root-scan.

_Install: ./install networklocation (dev by default)._

- Gradle: :networklocation / dir: networklocation/
- Package roots present: provider
- Entry files: MainActivity.kt
- Deps: :library:locationprovider, :library:downloadservice
- Metadata: metadata_data/networklocation.md (present)
- Rust: yes / screenshotTest: no

## Key files

- com/vayunmathur/networklocation/BeaconKeys.kt
- com/vayunmathur/networklocation/GeocoderNative.kt
- com/vayunmathur/networklocation/GeocodeService.kt
- com/vayunmathur/networklocation/MainActivity.kt
- com/vayunmathur/networklocation/NetworkLocationNative.kt
- com/vayunmathur/networklocation/NetworkLocationService.kt
- com/vayunmathur/networklocation/OfflineDatabases.kt
- com/vayunmathur/networklocation/OfflineDatabaseSection.kt
- com/vayunmathur/networklocation/PositioningData.kt
- com/vayunmathur/networklocation/WpsStoreNative.kt
- com/vayunmathur/networklocation/cache/BeaconCache.kt
- com/vayunmathur/networklocation/cache/TimedLruCache.kt
- com/vayunmathur/networklocation/cell/NearbyCells.kt
- com/vayunmathur/networklocation/provider/DatabaseStatusContract.kt
- com/vayunmathur/networklocation/provider/DatabaseStatusProvider.kt
- com/vayunmathur/networklocation/provider/LocationProviderImpl.kt
- com/vayunmathur/networklocation/provider/LocationReportingTask.kt
- com/vayunmathur/networklocation/provider/OfflineBeaconStore.kt
- com/vayunmathur/networklocation/wifi/NearbyWifi.kt

## Verify (this module only)
```
./gradlew :networklocation:compileDevKotlin
./gradlew :networklocation:lint
./gradlew :networklocation:checkMetadata
```


