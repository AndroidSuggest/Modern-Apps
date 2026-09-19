# AGENTS.md — speech/ (':speech')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `speech/` + allowed shared modules. Do not root-scan.

_Install: ./install speech (dev by default)._

- Gradle: :speech / dir: speech/
- Package roots present: domain, platform, service
- Entry files: MainActivity.kt
- Deps: :library:ml, :library:downloadservice
- Metadata: metadata_data/speech.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/speech/MainActivity.kt
- com/vayunmathur/speech/domain/SupertonicVoices.kt
- com/vayunmathur/speech/platform/SupertonicEngine.kt
- com/vayunmathur/speech/platform/SupertonicModel.kt
- com/vayunmathur/speech/service/SupertonicTtsService.kt
- com/vayunmathur/speech/service/WhisperRecognitionService.kt
- com/vayunmathur/speech/tts/CheckVoiceDataActivity.kt
- com/vayunmathur/speech/util/SpeechUiContract.kt
- com/vayunmathur/speech/util/WhisperEngine.kt
- com/vayunmathur/speech/util/WhisperFeatures.kt
- com/vayunmathur/speech/util/WhisperModel.kt
- com/vayunmathur/speech/util/WhisperTokenizer.kt

## Verify (this module only)
```
./gradlew :speech:compileDevKotlin
./gradlew :speech:lint
./gradlew :speech:checkMetadata
```


