# AGENTS.md — games/logicgate/ (':games:logicgate')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `games/logicgate/` + allowed shared modules. Do not root-scan.

_Install: ./install games/logicgate (dev by default)._

- Gradle: :games:logicgate / dir: games/logicgate/
- Package roots present: ui, data, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: 
- Metadata: metadata_data/games-logicgate.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/games/logicgate/MainActivity.kt
- com/vayunmathur/games/logicgate/Navigation.kt
- com/vayunmathur/games/logicgate/Route.kt
- com/vayunmathur/games/logicgate/data/ChipLibrary.kt
- com/vayunmathur/games/logicgate/data/CircuitModel.kt
- com/vayunmathur/games/logicgate/data/Levels.kt
- com/vayunmathur/games/logicgate/data/ProgressRepository.kt
- com/vayunmathur/games/logicgate/platform/Achievements.kt
- com/vayunmathur/games/logicgate/platform/AppBackupAgent.kt
- com/vayunmathur/games/logicgate/platform/LogicUiContract.kt
- com/vayunmathur/games/logicgate/platform/LogicViewModel.kt
- com/vayunmathur/games/logicgate/ui/CanvasTransform.kt
- com/vayunmathur/games/logicgate/ui/CircuitBackdrop.kt
- com/vayunmathur/games/logicgate/ui/CircuitCanvas.kt
- com/vayunmathur/games/logicgate/ui/CircuitGates.kt
- com/vayunmathur/games/logicgate/ui/CircuitWires.kt
- com/vayunmathur/games/logicgate/ui/GameEval.kt
- com/vayunmathur/games/logicgate/ui/GameInventory.kt
- com/vayunmathur/games/logicgate/ui/GameLabels.kt
- com/vayunmathur/games/logicgate/ui/GameLayout.kt
- com/vayunmathur/games/logicgate/ui/GamePage.kt
- com/vayunmathur/games/logicgate/ui/GamePanels.kt
- com/vayunmathur/games/logicgate/ui/GameScreen.kt
- com/vayunmathur/games/logicgate/ui/MobileTokens.kt
- com/vayunmathur/games/logicgate/ui/ProgressionPage.kt
- com/vayunmathur/games/logicgate/ui/ProgressionScreen.kt
- com/vayunmathur/games/logicgate/ui/Theme.kt
- com/vayunmathur/games/logicgate/ui/TuringGate.kt
- com/vayunmathur/games/logicgate/ui/TuringTerminal.kt
- com/vayunmathur/games/logicgate/ui/components/BitDotsRow.kt

## Verify (this module only)
```
./gradlew :games:logicgate:compileDevKotlin
./gradlew :games:logicgate:lint
./gradlew :games:logicgate:checkMetadata
```


