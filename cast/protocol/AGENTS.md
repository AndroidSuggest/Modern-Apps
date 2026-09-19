# AGENTS.md — cast/protocol/ (':cast:protocol')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `cast/protocol/` + allowed shared modules. Do not root-scan.

- Gradle: :cast:protocol / dir: cast/protocol/
- Package roots present: 
- Entry files: 
- Deps: :library, :library:e2ee-p2p
- Metadata: metadata_data/cast-protocol.md (MISSING)
- Rust: no / screenshotTest: no

## Key files

- com/vayunmathur/cast/protocol/CodecNegotiation.kt
- com/vayunmathur/cast/protocol/ControlFraming.kt
- com/vayunmathur/cast/protocol/Crypto.kt
- com/vayunmathur/cast/protocol/EphemeralTls.kt
- com/vayunmathur/cast/protocol/FrameAssembler.kt
- com/vayunmathur/cast/protocol/FrameId.kt
- com/vayunmathur/cast/protocol/HandshakeMessages.kt
- com/vayunmathur/cast/protocol/KeySchedule.kt
- com/vayunmathur/cast/protocol/MediaProxy.kt
- com/vayunmathur/cast/protocol/Pairing.kt
- com/vayunmathur/cast/protocol/ReceiverSession.kt
- com/vayunmathur/cast/protocol/Rtcp.kt
- com/vayunmathur/cast/protocol/RtpDepacketizer.kt
- com/vayunmathur/cast/protocol/RtpPacketizer.kt
- com/vayunmathur/cast/protocol/SecretSealing.kt
- com/vayunmathur/cast/protocol/StreamingSession.kt
- com/vayunmathur/cast/protocol/Streams.kt

## Verify (this module only)
```
./gradlew :cast:protocol:compileDevKotlin
./gradlew :cast:protocol:lint
```


