# AGENTS.md — games/nonogram/ (':games:nonogram')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `games/nonogram/` + allowed shared modules. Do not root-scan.

_Install: ./install games/nonogram (dev by default)._

- Gradle: :games:nonogram / dir: games/nonogram/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :sdk:games, :library:work
- Metadata: metadata_data/games-nonogram.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/games/nonogram/MainActivity.kt
- com/vayunmathur/games/nonogram/Navigation.kt
- com/vayunmathur/games/nonogram/Route.kt
- com/vayunmathur/games/nonogram/data/NonogramDataStore.kt
- com/vayunmathur/games/nonogram/data/NonogramModels.kt
- com/vayunmathur/games/nonogram/data/NonogramPuzzle.kt
- com/vayunmathur/games/nonogram/domain/NonogramGenerator.kt
- com/vayunmathur/games/nonogram/domain/NonogramSolver.kt
- com/vayunmathur/games/nonogram/platform/AppBackupAgent.kt
- com/vayunmathur/games/nonogram/platform/NonogramAchievementsManager.kt
- com/vayunmathur/games/nonogram/platform/NonogramUiContract.kt
- com/vayunmathur/games/nonogram/platform/NonogramViewModel.kt
- com/vayunmathur/games/nonogram/ui/NonogramGamePage.kt
- com/vayunmathur/games/nonogram/ui/NonogramGameScreen.kt
- com/vayunmathur/games/nonogram/ui/SettingsPage.kt
- com/vayunmathur/games/nonogram/ui/components/NonogramBoard.kt

## Verify (this module only)
```
./gradlew :games:nonogram:compileDevKotlin
./gradlew :games:nonogram:lint
./gradlew :games:nonogram:checkMetadata
```


