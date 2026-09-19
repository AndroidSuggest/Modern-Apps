# AGENTS.md — library/room/ (':library:room')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/room/` + allowed shared modules. Do not root-scan.

- Gradle: :library:room / dir: library/room/
- Package roots present: 
- Entry files: 
- Deps: :library
- Metadata: metadata_data/library-room.md (MISSING)
- Rust: no / screenshotTest: no

## Key files

- com/vayunmathur/library/room/RoomRepository.kt
- com/vayunmathur/library/room/SqlCipher.kt
- com/vayunmathur/library/room/SqlCipherDbCodec.kt

## Verify (this module only)
```
./gradlew :library:room:compileDevKotlin
./gradlew :library:room:lint
```


