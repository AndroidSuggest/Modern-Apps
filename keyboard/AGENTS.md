# AGENTS.md — keyboard/ (':keyboard')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `keyboard/` + allowed shared modules. Do not root-scan.

_Install: ./install keyboard (dev by default)._

- Gradle: :keyboard / dir: keyboard/
- Package roots present: ui, platform
- Entry files: MainActivity.kt
- Deps: 
- Metadata: metadata_data/keyboard.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/keyboard/MainActivity.kt
- com/vayunmathur/keyboard/ime/Composer.kt
- com/vayunmathur/keyboard/ime/EthiopicComposer.kt
- com/vayunmathur/keyboard/ime/HanComposer.kt
- com/vayunmathur/keyboard/ime/HangulComposer.kt
- com/vayunmathur/keyboard/ime/KanaComposers.kt
- com/vayunmathur/keyboard/ime/KeyboardService.kt
- com/vayunmathur/keyboard/ime/KeyboardState.kt
- com/vayunmathur/keyboard/ime/TextBeforeCursor.kt
- com/vayunmathur/keyboard/platform/VoiceInput.kt
- com/vayunmathur/keyboard/platform/VoicePermissionActivity.kt
- com/vayunmathur/keyboard/ui/ClipboardPage.kt
- com/vayunmathur/keyboard/ui/ClipboardStrip.kt
- com/vayunmathur/keyboard/ui/EmojiPage.kt
- com/vayunmathur/keyboard/ui/EmojiSearch.kt
- com/vayunmathur/keyboard/ui/FunctionKeys.kt
- com/vayunmathur/keyboard/ui/KeyboardBottomRow.kt
- com/vayunmathur/keyboard/ui/KeyboardLetters.kt
- com/vayunmathur/keyboard/ui/KeyboardScreen.kt
- com/vayunmathur/keyboard/ui/KeyboardSymbolPages.kt
- com/vayunmathur/keyboard/ui/KeyButton.kt
- com/vayunmathur/keyboard/ui/KeyPopups.kt
- com/vayunmathur/keyboard/ui/SetupScreen.kt
- com/vayunmathur/keyboard/ui/SuggestionStrip.kt
- com/vayunmathur/keyboard/ui/VoiceStrip.kt
- com/vayunmathur/keyboard/util/Clipboard.kt
- com/vayunmathur/keyboard/util/Dictionary.kt
- com/vayunmathur/keyboard/util/Emojis.kt
- com/vayunmathur/keyboard/util/KeyboardLayout.kt
- com/vayunmathur/keyboard/util/KeyboardLayouts.kt
- com/vayunmathur/keyboard/util/KeyboardLayoutsCyrillic.kt
- com/vayunmathur/keyboard/util/KeyboardLayoutsLatin.kt
- com/vayunmathur/keyboard/util/KeyboardLayoutsOther.kt
- com/vayunmathur/keyboard/util/KeyboardPrefs.kt
- com/vayunmathur/keyboard/util/Layouts.kt
- com/vayunmathur/keyboard/util/PinyinDictionary.kt

## Verify (this module only)
```
./gradlew :keyboard:compileDevKotlin
./gradlew :keyboard:lint
./gradlew :keyboard:checkMetadata
```


