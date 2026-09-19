# AGENTS.md — games/minesweeper/ (':games:minesweeper')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `games/minesweeper/` + allowed shared modules. Do not root-scan.

_Install: ./install games/minesweeper (dev by default)._

- Gradle: :games:minesweeper / dir: games/minesweeper/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :sdk:games
- Metadata: metadata_data/games-minesweeper.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/games/minesweeper/MainActivity.kt
- com/vayunmathur/games/minesweeper/Navigation.kt
- com/vayunmathur/games/minesweeper/Route.kt
- com/vayunmathur/games/minesweeper/data/MinesweeperModels.kt
- com/vayunmathur/games/minesweeper/data/MinesweeperStatsRepository.kt
- com/vayunmathur/games/minesweeper/domain/FieldGenerator.kt
- com/vayunmathur/games/minesweeper/domain/MinesweeperRules.kt
- com/vayunmathur/games/minesweeper/platform/AppBackupAgent.kt
- com/vayunmathur/games/minesweeper/platform/MinesweeperAchievementsManager.kt
- com/vayunmathur/games/minesweeper/platform/MinesweeperUiContract.kt
- com/vayunmathur/games/minesweeper/platform/MinesweeperViewModel.kt
- com/vayunmathur/games/minesweeper/ui/DisplayNames.kt
- com/vayunmathur/games/minesweeper/ui/GameBoardScreen.kt
- com/vayunmathur/games/minesweeper/ui/GameScreen.kt
- com/vayunmathur/games/minesweeper/ui/HomeScreen.kt
- com/vayunmathur/games/minesweeper/ui/components/MineFieldGrid.kt
- com/vayunmathur/games/minesweeper/ui/dialogs/GameConfigDialog.kt

## Verify (this module only)
```
./gradlew :games:minesweeper:compileDevKotlin
./gradlew :games:minesweeper:lint
./gradlew :games:minesweeper:checkMetadata
```


