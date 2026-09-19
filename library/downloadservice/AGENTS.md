# AGENTS.md — library/downloadservice/ (':library:downloadservice')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/downloadservice/` + allowed shared modules. Do not root-scan.

- Gradle: :library:downloadservice / dir: library/downloadservice/
- Package roots present: 
- Entry files: 
- Deps: :library, :library:work
- Metadata: metadata_data/library-downloadservice.md (MISSING)
- Rust: no / screenshotTest: no

## Key files

- com/vayunmathur/library/downloadservice/InitialDownloader.kt
- com/vayunmathur/library/downloadservice/ModelDownloadWorker.kt
- com/vayunmathur/library/downloadservice/ModelUrls.kt

## Verify (this module only)
```
./gradlew :library:downloadservice:compileDevKotlin
./gradlew :library:downloadservice:lint
```


