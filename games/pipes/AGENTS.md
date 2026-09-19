# AGENTS.md — games/pipes/ (':games:pipes')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `games/pipes/` + allowed shared modules. Do not root-scan.

_Install: ./install games/pipes (dev by default)._

- Gradle: :games:pipes / dir: games/pipes/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :sdk:games, :library:work
- Metadata: metadata_data/games-pipes.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/games/pipes/MainActivity.kt
- com/vayunmathur/games/pipes/Navigation.kt
- com/vayunmathur/games/pipes/Route.kt
- com/vayunmathur/games/pipes/data/CompletedLevelsRepository.kt
- com/vayunmathur/games/pipes/data/PipeModels.kt
- com/vayunmathur/games/pipes/domain/DailyLevels.kt
- com/vayunmathur/games/pipes/domain/LevelGenerator.kt
- com/vayunmathur/games/pipes/domain/NumberlinkSolver.kt
- com/vayunmathur/games/pipes/platform/AppBackupAgent.kt
- com/vayunmathur/games/pipes/platform/PipesAchievementsManager.kt
- com/vayunmathur/games/pipes/platform/PipesUiContract.kt
- com/vayunmathur/games/pipes/platform/PipesViewModel.kt
- com/vayunmathur/games/pipes/ui/DailyLevelScreen.kt
- com/vayunmathur/games/pipes/ui/GameBoard.kt
- com/vayunmathur/games/pipes/ui/GameBoardScreen.kt
- com/vayunmathur/games/pipes/ui/GameScreen.kt
- com/vayunmathur/games/pipes/ui/LevelScreen.kt
- com/vayunmathur/games/pipes/ui/PackListScreen.kt
- com/vayunmathur/games/pipes/ui/PackScreen.kt
- com/vayunmathur/games/pipes/ui/PipeColors.kt
- com/vayunmathur/games/pipes/ui/PipeRenderer.kt
- com/vayunmathur/games/pipes/ui/SettingsScreen.kt
- com/vayunmathur/games/pipes/ui/Theme.kt
- com/vayunmathur/games/pipes/ui/components/LevelThumbnail.kt

## Verify (this module only)
```
./gradlew :games:pipes:compileDevKotlin
./gradlew :games:pipes:lint
./gradlew :games:pipes:checkMetadata
```


