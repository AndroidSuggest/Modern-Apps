# AGENTS.md — games/alchemist/ (':games:alchemist')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `games/alchemist/` + allowed shared modules. Do not root-scan.

_Install: ./install games/alchemist (dev by default)._

- Gradle: :games:alchemist / dir: games/alchemist/
- Package roots present: ui, data, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :sdk:games
- Metadata: metadata_data/games-alchemist.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/games/alchemist/MainActivity.kt
- com/vayunmathur/games/alchemist/Navigation.kt
- com/vayunmathur/games/alchemist/Route.kt
- com/vayunmathur/games/alchemist/data/Alchemist.kt
- com/vayunmathur/games/alchemist/platform/AlchemistAchievementsManager.kt
- com/vayunmathur/games/alchemist/platform/AlchemistUiContract.kt
- com/vayunmathur/games/alchemist/platform/AlchemistViewModel.kt
- com/vayunmathur/games/alchemist/platform/AppBackupAgent.kt
- com/vayunmathur/games/alchemist/ui/AlchemistDraggable.kt
- com/vayunmathur/games/alchemist/ui/AlchemistInventory.kt
- com/vayunmathur/games/alchemist/ui/CollectionScreen.kt
- com/vayunmathur/games/alchemist/ui/HomeScreen.kt
- com/vayunmathur/games/alchemist/ui/ItemDetails.kt
- com/vayunmathur/games/alchemist/ui/components/DynamicAlchemyIcon.kt
- com/vayunmathur/games/alchemist/ui/components/UnlockNotification.kt

## Verify (this module only)
```
./gradlew :games:alchemist:compileDevKotlin
./gradlew :games:alchemist:lint
./gradlew :games:alchemist:checkMetadata
```


