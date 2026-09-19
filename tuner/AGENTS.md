# AGENTS.md — tuner/ (':tuner')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `tuner/` + allowed shared modules. Do not root-scan.

_Install: ./install tuner (dev by default)._

- Gradle: :tuner / dir: tuner/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: 
- Metadata: metadata_data/tuner.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/tuner/MainActivity.kt
- com/vayunmathur/tuner/Navigation.kt
- com/vayunmathur/tuner/Route.kt
- com/vayunmathur/tuner/data/Instruments.kt
- com/vayunmathur/tuner/domain/Cents.kt
- com/vayunmathur/tuner/domain/ChordDetector.kt
- com/vayunmathur/tuner/domain/ChordNaming.kt
- com/vayunmathur/tuner/domain/ChordPresenter.kt
- com/vayunmathur/tuner/domain/ConstantQ.kt
- com/vayunmathur/tuner/domain/Fft.kt
- com/vayunmathur/tuner/domain/HarmonicSieve.kt
- com/vayunmathur/tuner/domain/Nnls.kt
- com/vayunmathur/tuner/domain/PhaseSlope.kt
- com/vayunmathur/tuner/domain/PitchDetector.kt
- com/vayunmathur/tuner/domain/PresentationHold.kt
- com/vayunmathur/tuner/domain/Tonality.kt
- com/vayunmathur/tuner/domain/TuningOffset.kt
- com/vayunmathur/tuner/domain/VoicingGenerator.kt
- com/vayunmathur/tuner/domain/Yin.kt
- com/vayunmathur/tuner/platform/AudioCapture.kt
- com/vayunmathur/tuner/platform/TunerUiContract.kt
- com/vayunmathur/tuner/platform/TunerViewModel.kt
- com/vayunmathur/tuner/ui/ChordPage.kt
- com/vayunmathur/tuner/ui/ChordScreen.kt
- com/vayunmathur/tuner/ui/FretDiagram.kt
- com/vayunmathur/tuner/ui/InstrumentMenu.kt
- com/vayunmathur/tuner/ui/NotePage.kt
- com/vayunmathur/tuner/ui/NoteScreen.kt
- com/vayunmathur/tuner/ui/PianoKeyboard.kt
- com/vayunmathur/tuner/ui/TunerTabs.kt
- com/vayunmathur/tuner/ui/TuningMeter.kt

## Verify (this module only)
```
./gradlew :tuner:compileDevKotlin
./gradlew :tuner:lint
./gradlew :tuner:checkMetadata
```


