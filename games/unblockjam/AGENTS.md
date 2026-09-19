# AGENTS.md — games/unblockjam/ (':games:unblockjam')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `games/unblockjam/` + allowed shared modules. Do not root-scan.

_Install: ./install games/unblockjam (dev by default)._

- Gradle: :games:unblockjam / dir: games/unblockjam/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :sdk:games, :library:work
- Metadata: metadata_data/games-unblockjam.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/games/unblockjam/MainActivity.kt
- com/vayunmathur/games/unblockjam/Navigation.kt
- com/vayunmathur/games/unblockjam/Route.kt
- com/vayunmathur/games/unblockjam/data/CompletedLevelsRepository.kt
- com/vayunmathur/games/unblockjam/data/DailyLevelGenerator.kt
- com/vayunmathur/games/unblockjam/data/LevelData.kt
- com/vayunmathur/games/unblockjam/domain/RushHourSolver.kt
- com/vayunmathur/games/unblockjam/platform/AppBackupAgent.kt
- com/vayunmathur/games/unblockjam/platform/BlockDragGestures.kt
- com/vayunmathur/games/unblockjam/platform/GameUiContract.kt
- com/vayunmathur/games/unblockjam/platform/UnblockJamAchievementsManager.kt
- com/vayunmathur/games/unblockjam/platform/UnblockJamViewModel.kt
- com/vayunmathur/games/unblockjam/ui/Color.kt
- com/vayunmathur/games/unblockjam/ui/Theme.kt
- com/vayunmathur/games/unblockjam/ui/Type.kt

## Verify (this module only)
```
./gradlew :games:unblockjam:compileDevKotlin
./gradlew :games:unblockjam:lint
./gradlew :games:unblockjam:checkMetadata
```


