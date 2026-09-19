# AGENTS.md — games/sudoku/ (':games:sudoku')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `games/sudoku/` + allowed shared modules. Do not root-scan.

_Install: ./install games/sudoku (dev by default)._

- Gradle: :games:sudoku / dir: games/sudoku/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :sdk:games
- Metadata: metadata_data/games-sudoku.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/games/sudoku/MainActivity.kt
- com/vayunmathur/games/sudoku/Navigation.kt
- com/vayunmathur/games/sudoku/Route.kt
- com/vayunmathur/games/sudoku/data/SudokuModels.kt
- com/vayunmathur/games/sudoku/data/SudokuStatsRepository.kt
- com/vayunmathur/games/sudoku/domain/SudokuGenerator.kt
- com/vayunmathur/games/sudoku/domain/SudokuSolver.kt
- com/vayunmathur/games/sudoku/platform/AppBackupAgent.kt
- com/vayunmathur/games/sudoku/platform/SudokuAchievementsManager.kt
- com/vayunmathur/games/sudoku/platform/SudokuUiContract.kt
- com/vayunmathur/games/sudoku/platform/SudokuViewModel.kt
- com/vayunmathur/games/sudoku/ui/DisplayNames.kt
- com/vayunmathur/games/sudoku/ui/GameBoardScreen.kt
- com/vayunmathur/games/sudoku/ui/GameScreen.kt
- com/vayunmathur/games/sudoku/ui/HomeScreen.kt
- com/vayunmathur/games/sudoku/ui/components/NumberPad.kt
- com/vayunmathur/games/sudoku/ui/components/SudokuGrid.kt
- com/vayunmathur/games/sudoku/ui/dialogs/GameConfigDialog.kt

## Verify (this module only)
```
./gradlew :games:sudoku:compileDevKotlin
./gradlew :games:sudoku:lint
./gradlew :games:sudoku:checkMetadata
```


