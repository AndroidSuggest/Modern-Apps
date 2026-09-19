# AGENTS.md — library/network/ (':library:network')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/network/` + allowed shared modules. Do not root-scan.

- Gradle: :library:network / dir: library/network/
- Package roots present: network
- Entry files: 
- Deps: 
- Metadata: metadata_data/library-network.md (MISSING)
- Rust: no / screenshotTest: no

## Key files

- com/vayunmathur/library/network/BundledTrust.kt
- com/vayunmathur/library/network/HttpUrlEngine.kt
- com/vayunmathur/library/network/NativeHttpBridge.kt
- com/vayunmathur/library/network/NetworkClient.kt
- com/vayunmathur/library/network/TrustBundle.kt
- com/vayunmathur/library/network/Urls.kt
- com/vayunmathur/library/network/WebSocketClient.kt
- com/vayunmathur/library/util/ConnectivityMonitor.kt

## Verify (this module only)
```
./gradlew :library:network:compileDevKotlin
./gradlew :library:network:lint
```


