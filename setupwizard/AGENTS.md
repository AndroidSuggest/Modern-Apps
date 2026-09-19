# AGENTS.md — setupwizard/ (':setupwizard')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `setupwizard/` + allowed shared modules. Do not root-scan.

_Install: ./install setupwizard (dev by default)._

- Gradle: :setupwizard / dir: setupwizard/
- Package roots present: ui, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: 
- Metadata: metadata_data/setupwizard.md (present)
- Rust: no / screenshotTest: no

## Key files

- com/vayunmathur/setupwizard/MainActivity.kt
- com/vayunmathur/setupwizard/Navigation.kt
- com/vayunmathur/setupwizard/Route.kt
- com/vayunmathur/setupwizard/platform/DebugFlags.kt
- com/vayunmathur/setupwizard/platform/SetupFlow.kt
- com/vayunmathur/setupwizard/platform/SetupIntents.kt
- com/vayunmathur/setupwizard/platform/SetupViewModel.kt
- com/vayunmathur/setupwizard/platform/SystemSetup.kt
- com/vayunmathur/setupwizard/ui/FinishScreen.kt
- com/vayunmathur/setupwizard/ui/GesturesScreen.kt
- com/vayunmathur/setupwizard/ui/HandoffScreen.kt
- com/vayunmathur/setupwizard/ui/LocationScreen.kt
- com/vayunmathur/setupwizard/ui/MigrationScreen.kt
- com/vayunmathur/setupwizard/ui/OemUnlockScreen.kt
- com/vayunmathur/setupwizard/ui/WelcomeScreen.kt
- com/vayunmathur/setupwizard/ui/components/SetupCheckboxRow.kt
- com/vayunmathur/setupwizard/ui/dialogs/LanguagePickerDialog.kt

## Verify (this module only)
```
./gradlew :setupwizard:compileDevKotlin
./gradlew :setupwizard:lint
./gradlew :setupwizard:checkMetadata
```


