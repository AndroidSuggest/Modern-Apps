# AGENTS.md — openassistant/ (':openassistant')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `openassistant/` + allowed shared modules. Do not root-scan.

_Install: ./install openassistant (dev by default)._

- Gradle: :openassistant / dir: openassistant/
- Package roots present: ui, data
- Entry files: MainActivity.kt
- Deps: :library:room, :library:image, :library:ml, :library:downloadservice
- Metadata: metadata_data/openassistant.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/openassistant/MainActivity.kt
- com/vayunmathur/openassistant/assist/OpenAssistantSession.kt
- com/vayunmathur/openassistant/assist/OpenAssistantSessionService.kt
- com/vayunmathur/openassistant/assist/OpenAssistantVoiceInteractionService.kt
- com/vayunmathur/openassistant/assist/StubRecognitionService.kt
- com/vayunmathur/openassistant/data/Database.kt
- com/vayunmathur/openassistant/data/OpenAssistantRepository.kt
- com/vayunmathur/openassistant/ui/AssistantChatBubble.kt
- com/vayunmathur/openassistant/ui/AssistantChatInput.kt
- com/vayunmathur/openassistant/ui/AssistantChatUi.kt
- com/vayunmathur/openassistant/ui/SettingsPage.kt
- com/vayunmathur/openassistant/util/AppBackupAgent.kt
- com/vayunmathur/openassistant/util/AssistantToolSet.kt
- com/vayunmathur/openassistant/util/AssistantUiContract.kt
- com/vayunmathur/openassistant/util/AssistantViewModel.kt
- com/vayunmathur/openassistant/util/AudioRecorder.kt
- com/vayunmathur/openassistant/util/Gemma4Engine.kt
- com/vayunmathur/openassistant/util/Gemma4EtEngine.kt
- com/vayunmathur/openassistant/util/InferenceService.kt

## Verify (this module only)
```
./gradlew :openassistant:compileDevKotlin
./gradlew :openassistant:lint
./gradlew :openassistant:checkMetadata
```


