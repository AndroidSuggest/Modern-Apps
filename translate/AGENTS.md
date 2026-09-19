# AGENTS.md — translate/ (':translate')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `translate/` + allowed shared modules. Do not root-scan.

_Install: ./install translate (dev by default)._

- Gradle: :translate / dir: translate/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:ml, :library:downloadservice, :library:ocr, :library:network
- Metadata: metadata_data/translate.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/translate/MainActivity.kt
- com/vayunmathur/translate/Navigation.kt
- com/vayunmathur/translate/Route.kt
- com/vayunmathur/translate/data/TranslateSettings.kt
- com/vayunmathur/translate/domain/Languages.kt
- com/vayunmathur/translate/domain/RecentLanguages.kt
- com/vayunmathur/translate/domain/TranslationEngine.kt
- com/vayunmathur/translate/platform/NllbModel.kt
- com/vayunmathur/translate/platform/NllbTranslator.kt
- com/vayunmathur/translate/platform/SpeechRecognizerEngine.kt
- com/vayunmathur/translate/platform/TranslateUiContract.kt
- com/vayunmathur/translate/platform/TranslateViewModel.kt
- com/vayunmathur/translate/platform/TtsSpeaker.kt
- com/vayunmathur/translate/ui/CameraBinding.kt
- com/vayunmathur/translate/ui/CameraFrameUtils.kt
- com/vayunmathur/translate/ui/CameraOverlay.kt
- com/vayunmathur/translate/ui/CameraPreviewLayer.kt
- com/vayunmathur/translate/ui/CameraTranslateScreen.kt
- com/vayunmathur/translate/ui/LanguagePickerPage.kt
- com/vayunmathur/translate/ui/LanguagePickerScreen.kt
- com/vayunmathur/translate/ui/TextTranslatePage.kt
- com/vayunmathur/translate/ui/TextTranslateScreen.kt

## Verify (this module only)
```
./gradlew :translate:compileDevKotlin
./gradlew :translate:lint
./gradlew :translate:checkMetadata
```


