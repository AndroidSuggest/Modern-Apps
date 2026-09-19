# AGENTS.md — games/arrows/ (':games:arrows')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `games/arrows/` + allowed shared modules. Do not root-scan.

_Install: ./install games/arrows (dev by default)._

- Gradle: :games:arrows / dir: games/arrows/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :sdk:games, :library:work
- Metadata: metadata_data/games-arrows.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/games/arrows/MainActivity.kt
- com/vayunmathur/games/arrows/Navigation.kt
- com/vayunmathur/games/arrows/Route.kt
- com/vayunmathur/games/arrows/data/ArrowModels.kt
- com/vayunmathur/games/arrows/data/ArrowsDataStore.kt
- com/vayunmathur/games/arrows/domain/ArrowsGenerator.kt
- com/vayunmathur/games/arrows/domain/ArrowsRules.kt
- com/vayunmathur/games/arrows/platform/AppBackupAgent.kt
- com/vayunmathur/games/arrows/platform/ArrowsAchievementsManager.kt
- com/vayunmathur/games/arrows/platform/ArrowsUiContract.kt
- com/vayunmathur/games/arrows/platform/ArrowsViewModel.kt
- com/vayunmathur/games/arrows/ui/ArrowsGamePage.kt
- com/vayunmathur/games/arrows/ui/ArrowsGameScreen.kt
- com/vayunmathur/games/arrows/ui/SettingsPage.kt
- com/vayunmathur/games/arrows/ui/components/ArrowsBoard.kt
- com/vayunmathur/games/arrows/ui/components/ArrowsBoardDrawing.kt

## Verify (this module only)
```
./gradlew :games:arrows:compileDevKotlin
./gradlew :games:arrows:lint
./gradlew :games:arrows:checkMetadata
```


