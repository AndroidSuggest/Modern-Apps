# AGENTS.md — library/media/ (':library:media')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/media/` + allowed shared modules. Do not root-scan.

- Gradle: :library:media / dir: library/media/
- Package roots present: 
- Entry files: 
- Deps: 
- Metadata: metadata_data/library-media.md (MISSING)
- Rust: no / screenshotTest: no

## Key files

- com/vayunmathur/library/media/ByteArrayMediaDataSource.kt
- com/vayunmathur/library/media/OggOpusTagger.kt
- com/vayunmathur/library/media/OggStreamWriter.kt
- com/vayunmathur/library/media/OpusHead.kt
- com/vayunmathur/library/media/OpusRemuxer.kt
- com/vayunmathur/library/media/OpusTranscoder.kt
- com/vayunmathur/library/media/PcmBuffers.kt
- com/vayunmathur/library/media/PolyphaseResampler.kt
- com/vayunmathur/library/media/VorbisComments.kt

## Verify (this module only)
```
./gradlew :library:media:compileDevKotlin
./gradlew :library:media:lint
```


