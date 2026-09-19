# AGENTS.md — games/chess/ (':games:chess')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `games/chess/` + allowed shared modules. Do not root-scan.

_Install: ./install games/chess (dev by default)._

- Gradle: :games:chess / dir: games/chess/
- Package roots present: data
- Entry files: MainActivity.kt, Route.kt
- Deps: :sdk:games, :library:ml
- Metadata: metadata_data/games-chess.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/games/chess/ChessGameScreens.kt
- com/vayunmathur/games/chess/ChessLearnScreens.kt
- com/vayunmathur/games/chess/ChessPuzzleScreens.kt
- com/vayunmathur/games/chess/MainActivity.kt
- com/vayunmathur/games/chess/Route.kt
- com/vayunmathur/games/chess/data/ChessModel.kt
- com/vayunmathur/games/chess/data/Learn.kt
- com/vayunmathur/games/chess/data/Puzzle.kt
- com/vayunmathur/games/chess/util/AppBackupAgent.kt
- com/vayunmathur/games/chess/util/ChessAchievementsManager.kt
- com/vayunmathur/games/chess/util/ChessApi.kt
- com/vayunmathur/games/chess/util/ChessUiContract.kt
- com/vayunmathur/games/chess/util/ChessViewModel.kt
- com/vayunmathur/games/chess/util/LearnViewModel.kt
- com/vayunmathur/games/chess/util/MaiaEngine.kt
- com/vayunmathur/games/chess/util/PuzzleViewModel.kt

## Verify (this module only)
```
./gradlew :games:chess:compileDevKotlin
./gradlew :games:chess:lint
./gradlew :games:chess:checkMetadata
```


