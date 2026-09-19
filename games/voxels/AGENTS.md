# AGENTS.md — games/voxels/ (':games:voxels')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `games/voxels/` + allowed shared modules. Do not root-scan.

_Install: ./install games/voxels (dev by default)._

- Gradle: :games:voxels / dir: games/voxels/
- Package roots present: ui, data, platform, network
- Entry files: MainActivity.kt
- Deps: :sdk:games, :library:network, :library:e2ee-p2p
- Metadata: metadata_data/games-voxels.md (present)
- Rust: yes / screenshotTest: no

## Key files

- com/vayunmathur/games/voxels/MainActivity.kt
- com/vayunmathur/games/voxels/data/WorldManager.kt
- com/vayunmathur/games/voxels/network/VoxelsSync.kt
- com/vayunmathur/games/voxels/platform/AppBackupAgent.kt
- com/vayunmathur/games/voxels/platform/MenuActivity.kt
- com/vayunmathur/games/voxels/platform/MusicFx.kt
- com/vayunmathur/games/voxels/platform/SoundFx.kt
- com/vayunmathur/games/voxels/platform/VoxelsAchievementsManager.kt
- com/vayunmathur/games/voxels/platform/VoxelsNative.kt
- com/vayunmathur/games/voxels/ui/FurnaceScreen.kt
- com/vayunmathur/games/voxels/ui/Hotbar.kt
- com/vayunmathur/games/voxels/ui/InventoryBlessings.kt
- com/vayunmathur/games/voxels/ui/InventoryCrafting.kt
- com/vayunmathur/games/voxels/ui/InventoryScreen.kt
- com/vayunmathur/games/voxels/ui/Joystick.kt
- com/vayunmathur/games/voxels/ui/MenuScreens.kt
- com/vayunmathur/games/voxels/ui/StonecutterScreen.kt
- com/vayunmathur/games/voxels/ui/Theme.kt
- com/vayunmathur/games/voxels/ui/TradeScreen.kt
- com/vayunmathur/games/voxels/ui/VoxelCatalog.kt
- com/vayunmathur/games/voxels/ui/VoxelSurfaceView.kt

## Verify (this module only)
```
./gradlew :games:voxels:compileDevKotlin
./gradlew :games:voxels:lint
./gradlew :games:voxels:checkMetadata
```


