# AGENTS.md — auto/protocol/ (':auto:protocol')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `auto/protocol/` + allowed shared modules. Do not root-scan.

- Gradle: :auto:protocol / dir: auto/protocol/
- Package roots present: 
- Entry files: 
- Deps: :library
- Metadata: metadata_data/auto-protocol.md (MISSING)
- Rust: no / screenshotTest: no

## Key files

- com/vayunmathur/auto/protocol/AckTracker.kt
- com/vayunmathur/auto/protocol/AudioCodec.kt
- com/vayunmathur/auto/protocol/ChannelSendQueue.kt
- com/vayunmathur/auto/protocol/DisplayRoutePolicy.kt
- com/vayunmathur/auto/protocol/FocusArbitration.kt
- com/vayunmathur/auto/protocol/Fragmenter.kt
- com/vayunmathur/auto/protocol/FrameHeader.kt
- com/vayunmathur/auto/protocol/FrameReader.kt
- com/vayunmathur/auto/protocol/FrameWriter.kt
- com/vayunmathur/auto/protocol/GalConnection.kt
- com/vayunmathur/auto/protocol/GalControlSession.kt
- com/vayunmathur/auto/protocol/GalCredential.kt
- com/vayunmathur/auto/protocol/GalMessage.kt
- com/vayunmathur/auto/protocol/GalTransport.kt
- com/vayunmathur/auto/protocol/HostVsMirror.kt
- com/vayunmathur/auto/protocol/InputCodec.kt
- com/vayunmathur/auto/protocol/MaosRole.kt
- com/vayunmathur/auto/protocol/MapsGuidance.kt
- com/vayunmathur/auto/protocol/MessageCodec.kt
- com/vayunmathur/auto/protocol/MessagingCodec.kt
- com/vayunmathur/auto/protocol/NavStatusCodec.kt
- com/vayunmathur/auto/protocol/ReconnectBackoff.kt
- com/vayunmathur/auto/protocol/SensorCodec.kt
- com/vayunmathur/auto/protocol/StreamTransport.kt
- com/vayunmathur/auto/protocol/TlsCodec.kt
- com/vayunmathur/auto/protocol/TransportKind.kt
- com/vayunmathur/auto/protocol/TransportSelector.kt
- com/vayunmathur/auto/protocol/UsbSession.kt
- com/vayunmathur/auto/protocol/VersionNegotiation.kt
- com/vayunmathur/auto/protocol/VideoCodec.kt
- com/vayunmathur/auto/protocol/WirelessSession.kt

## Verify (this module only)
```
./gradlew :auto:protocol:compileDevKotlin
./gradlew :auto:protocol:lint
```


