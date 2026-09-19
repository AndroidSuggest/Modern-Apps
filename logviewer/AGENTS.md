# AGENTS.md — logviewer/ (':logviewer')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `logviewer/` + allowed shared modules. Do not root-scan.

_Install: ./install logviewer (dev by default)._

- Gradle: :logviewer / dir: logviewer/
- Package roots present: ui, data, domain, platform, intents, provider
- Entry files: 
- Deps: 
- Metadata: metadata_data/logviewer.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/logviewer/data/TombstoneFiles.kt
- com/vayunmathur/logviewer/domain/LogDocument.kt
- com/vayunmathur/logviewer/domain/LogKind.kt
- com/vayunmathur/logviewer/domain/LogLevel.kt
- com/vayunmathur/logviewer/intents/ErrorReportActivity.kt
- com/vayunmathur/logviewer/intents/LogcatActivity.kt
- com/vayunmathur/logviewer/intents/LogViewerIntents.kt
- com/vayunmathur/logviewer/platform/ErrorReportReader.kt
- com/vayunmathur/logviewer/platform/HiddenFrameworkApi.kt
- com/vayunmathur/logviewer/platform/LogcatReader.kt
- com/vayunmathur/logviewer/platform/LogLoadResult.kt
- com/vayunmathur/logviewer/platform/LogViewerController.kt
- com/vayunmathur/logviewer/platform/LogViewerUiContract.kt
- com/vayunmathur/logviewer/platform/LogViewerViewModel.kt
- com/vayunmathur/logviewer/platform/ReportHeaders.kt
- com/vayunmathur/logviewer/provider/BlobProvider.kt
- com/vayunmathur/logviewer/ui/LogViewerPage.kt
- com/vayunmathur/logviewer/ui/LogViewerScreen.kt
- com/vayunmathur/logviewer/ui/components/LogLineList.kt
- com/vayunmathur/logviewer/ui/dialogs/LogBuffersDialog.kt
- com/vayunmathur/logviewer/ui/dialogs/LogLevelDialog.kt
- com/vayunmathur/logviewer/ui/dialogs/StackTraceDialog.kt
- com/vayunmathur/logviewer/ui/dialogs/TextInputDialog.kt

## Verify (this module only)
```
./gradlew :logviewer:compileDevKotlin
./gradlew :logviewer:lint
./gradlew :logviewer:checkMetadata
```


