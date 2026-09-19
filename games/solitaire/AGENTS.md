# AGENTS.md — games/solitaire/ (':games:solitaire')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `games/solitaire/` + allowed shared modules. Do not root-scan.

_Install: ./install games/solitaire (dev by default)._

- Gradle: :games:solitaire / dir: games/solitaire/
- Package roots present: ui, data, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :sdk:games
- Metadata: metadata_data/games-solitaire.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/games/solitaire/MainActivity.kt
- com/vayunmathur/games/solitaire/Navigation.kt
- com/vayunmathur/games/solitaire/Route.kt
- com/vayunmathur/games/solitaire/data/CardColorScheme.kt
- com/vayunmathur/games/solitaire/data/CardModels.kt
- com/vayunmathur/games/solitaire/data/GameState.kt
- com/vayunmathur/games/solitaire/data/SolitaireSettingsRepository.kt
- com/vayunmathur/games/solitaire/data/SolitaireStatsRepository.kt
- com/vayunmathur/games/solitaire/platform/AppBackupAgent.kt
- com/vayunmathur/games/solitaire/platform/SolitaireAchievementsManager.kt
- com/vayunmathur/games/solitaire/platform/SolitaireDragOps.kt
- com/vayunmathur/games/solitaire/platform/SolitaireFreeCellOps.kt
- com/vayunmathur/games/solitaire/platform/SolitaireKlondikeOps.kt
- com/vayunmathur/games/solitaire/platform/SolitairePyramidOps.kt
- com/vayunmathur/games/solitaire/platform/SolitaireSpiderOps.kt
- com/vayunmathur/games/solitaire/platform/SolitaireUiContract.kt
- com/vayunmathur/games/solitaire/platform/SolitaireViewModel.kt
- com/vayunmathur/games/solitaire/ui/CardView.kt
- com/vayunmathur/games/solitaire/ui/DragAndDrop.kt
- com/vayunmathur/games/solitaire/ui/FoundationView.kt
- com/vayunmathur/games/solitaire/ui/FreeCellBoard.kt
- com/vayunmathur/games/solitaire/ui/GameActionBar.kt
- com/vayunmathur/games/solitaire/ui/GameBoardScreen.kt
- com/vayunmathur/games/solitaire/ui/GameModeDisplayName.kt
- com/vayunmathur/games/solitaire/ui/GameScreen.kt
- com/vayunmathur/games/solitaire/ui/HomeScreen.kt
- com/vayunmathur/games/solitaire/ui/KlondikeBoard.kt
- com/vayunmathur/games/solitaire/ui/PyramidBoard.kt
- com/vayunmathur/games/solitaire/ui/SettingsScreen.kt
- com/vayunmathur/games/solitaire/ui/SpiderBoard.kt
- com/vayunmathur/games/solitaire/ui/TableauColumn.kt
- com/vayunmathur/games/solitaire/ui/WinOverlay.kt
- com/vayunmathur/games/solitaire/ui/dialogs/GameConfigDialog.kt

## Verify (this module only)
```
./gradlew :games:solitaire:compileDevKotlin
./gradlew :games:solitaire:lint
./gradlew :games:solitaire:checkMetadata
```


