# AGENTS.md — sdk/cast/ (':sdk:cast')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `sdk/cast/` + allowed shared modules. Do not root-scan.

- Gradle: :sdk:cast / dir: sdk/cast/
- Package roots present: 
- Entry files: 
- Deps: 
- Metadata: metadata_data/sdk-cast.md (MISSING)
- Rust: no / screenshotTest: no

## Key files

- com/vayunmathur/sdk/cast/CastClient.kt
- com/vayunmathur/sdk/cast/CastContract.kt
- com/vayunmathur/sdk/cast/CastPickerContract.kt
- com/vayunmathur/sdk/cast/CastResource.kt
- com/vayunmathur/sdk/cast/Exceptions.kt
- com/vayunmathur/sdk/cast/Playback.kt

## Verify (this module only)
```
./gradlew :sdk:cast:compileDevKotlin
./gradlew :sdk:cast:lint
```


