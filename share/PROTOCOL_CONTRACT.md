# Share — JNI Protocol Contract (Kotlin ↔ Rust)

This is the **single source of truth** for the session API between the Kotlin app layer
(Compose UI, NSD/BLE discovery, TCP transport — **appdev**) and the Rust protocol crate
(UKEY2, protobuf framing, secure messages, payload — **rustdev**).

> Do not change native method names, signatures, or the crate/library names
> without updating this doc, `ShareNative.kt`, *and* `share/src/main/rust/src/lib.rs`
> together. Renames break JNI linkage at runtime.

## 1. Crate & library

- **Cargo crate:** `share_nearby` at `share/src/main/rust/` (members entry in root `Cargo.toml`).
- **Shared lib:** `libshare_nearby.so` built via `rustNativeLib("share_nearby", "share")` in
  `share/build.gradle.kts` and wired into `android { ... jniLibs sourceDirectory }` (copy
  `maps` / `library:e2ee-p2p`). Crate exposes `crate-type = ["cdylib","rlib"]`.
- **Kotlin JNI class:** `com.vayunmathur.share.protocol.ShareNative` (the `System.loadLibrary`
  is `share_nearby`).

## 2. Ownership & threading

- **Kotlin owns:** BLE + NSD advertisement/scanning, TCP listen/connect, file I/O (SAF/MediaStore),
  foreground notification, permissions.
- **Rust owns:** UKEY2 handshake, frame encode/decode, secure-message encrypt/decrypt,
  `Introduction` + payload state machine, per-session outbound queue.
- **Transport rule:** Kotlin never hands Rust a socket and Rust never performs I/O. All bytes
  cross as `byte[]` via `feedInbound` / `drainOutbound`.
- **Threading:** Calls for a given session handle must be serialized by the caller (one
  coroutine / thread per session). Rust guards the session map with a `Mutex`, but callers
  should still avoid concurrent `feedInbound` + `drainOutbound` on the same handle.

## 3. Session lifecycle (call order)

```
handle = nativeInit(localName, localEndpointInfo)   // 1. create session
loop {
  bytes = tcpSocket.read()                           // blocking read
  if (bytes != null) nativeFeedInbound(handle, bytes)
  while ((out = nativeDrainOutbound(handle)) != null) tcpSocket.write(out)
  // drive UI from nativeQueryState / nativeQueryPendingFiles
}
nativeAccept(handle, userAccepted, destDir)          // when state == AWAITING_ACCEPT
// during TRANSFERRING, per file:
nativeOpenFile(handle, name, size); nativeWriteChunk(handle, chunk)*; nativeCloseFile(handle)
nativeDestroy(handle)                                // always (finally / onCleared)
```

Errors are returned as negative `int` codes from the `native* -> int` methods; a null
`byte[]` from `drainOutbound` / `queryPendingFiles` means "nothing / no session".

## 4. State machine

`nativeQueryState` returns the `State` ordinal below. Kotlin maps it via `ShareState`.

| Code | Name             | Meaning |
|------|------------------|---------|
| 0 | `Handshaking`     | UKEY2 in progress; keep pumping bytes. |
| 1 | `AwaitingAccept`  | Introduction decoded; UI should surface `queryPendingFiles` and prompt Accept/Reject. |
| 2 | `Transferring`    | Accepted; file bytes are flowing (use `openFile`/`writeChunk`/`closeFile`). |
| 3 | `Completed`       | All files transferred + secure-message closed cleanly. |
| 4 | `Failed`          | Handshake/auth/decrypt failure or user rejected. |

## 5. JNI surface (exact signatures)

Class: `com.vayunmathur.share.protocol.ShareNative`
Rust symbols: `Java_com_vayunmathur_share_protocol_ShareNative_<method>` (`extern "system"`).

```kotlin
object ShareNative {
  external fun nativeInit(localName: String, localEndpointInfo: ByteArray): Long
  external fun nativeFeedInbound(handle: Long, bytes: ByteArray): Int
  external fun nativeDrainOutbound(handle: Long): ByteArray?         // null = nothing to send
  external fun nativeQueryState(handle: Long): Int                   // State code, -1 = bad handle
  external fun nativeQueryPendingFiles(handle: Long): ByteArray?     // JSON utf8, see §6
  external fun nativeAccept(handle: Long, accept: Boolean, destDir: String): Int
  external fun nativeOpenFile(handle: Long, fileName: String, fileSize: Long): Int
  external fun nativeWriteChunk(handle: Long, chunk: ByteArray): Int
  external fun nativeCloseFile(handle: Long): Int
  external fun nativeDestroy(handle: Long)                           // void
}
```

Kotlin also ships `ShareSession` (`share/protocol/ShareSession.kt`) as the
`AutoCloseable` wrapper (handle lifetime, JSON decode, `ShareState` mapping) — the
preferred call site for app code. Direct `ShareNative` calls are allowed but
`ShareSession` should be used for lifecycle correctness.

## 6. Data formats

- **Bytes:** All wire frames are opaque `byte[]`. Kotlin does no framing; Rust
  is responsible for varint length-prefix, protobuf decode/encode, and buffering
  partial reads internally.
- **`queryPendingFiles`:** UTF-8 JSON `byte[]` — `[{"name":"photo.jpg","sizeBytes":1234,"mimeType":"image/jpeg"}]`.
  Empty list is `[]` or (contextually) the skeleton may return a stub; rustdev
  must populate real Introduction metadata before shipping.
- **Files:** Kotlin opens the destination file (SAF/MediaStore/app-cache as
  appropriate) and Rust streams decrypted payload bytes via `writeChunk`. The
  current skeleton `nativeOpenFile`/`nativeWriteChunk`/`nativeCloseFile` are
  placeholders that ack — rustdev should wire them to the secure-message decryptor
  and return `<0` on auth failure.
- **Errors:** `nativeFeedInbound` / `nativeAccept` / `native*File` return `0` on
  success, `<0` on error (`-1` bad handle/args, `-2` wrong state, etc.). The
  caller should transition to `Failed` and surface the error.

## 7. What rustdev must implement

Inside `share/src/main/rust/src/` (suggested split):

- `handshake.rs` — UKEY2 (X25519 + commitment).
- `frame.rs` — varint/length-prefix + protobuf (e.g. `prost`) encode/decode.
- `secure.rs` — secure-message key derivation + AES-GCM/Secp encrypt/decrypt.
- `payload.rs` — Introduction / FileMetadata / payload chunk state.
- `session.rs` — `Session` struct + outbound queue + state transitions (the glue
  currently in `lib.rs`'s `SESSIONS` map).

Keep the `lib.rs` JNI names/handles/error codes/state ordinals stable. Extend
the session struct in place; do not change `ShareNative`'s Kotlin signatures
without bumping this doc.

## 8. What appdev must implement

- `MainActivity` + Compose screens (using `:library:ui` — direct
  `androidx.compose.material*` imports are banned by lint) for:
  discovery/teaming (BLE + NSD/mDNS), nearby list, incoming banner (Accept/Reject),
  progress, and completion. Wire `ShareSession` in a `ViewModel` (one session per peer).
- TCP transport: listen on ephemeral port, advertise via BLE/NSD, connect to peer,
  pump `feedInbound`/`drainOutbound` on a background dispatcher, post state to UI via
  `queryState` polling or callbacks.
- File I/O: destination directory picker (SAF), creating file entries, `openFile`/
  `writeChunk`/`closeFile` sequence during `Transferring`.
- Foreground service `ShareTransferService` for transfers that outlive the Activity.
- Permissions flow: `NEARBY_WIFI_DEVICES` / `BLUETOOTH_*` / `ACCESS_FINE_LOCATION`
  (see `AndroidManifest.xml`), and the `ACTION_SEND` / `ACTION_SEND_MULTIPLE`
  incoming share intents.

## 9. Build & verify

```powershell
# Build just the Share module (no other apps):
./gradlew :share:assembleDev

# Lint (Material-via-:library:ui, manifests, etc.):
./gradlew :share:lintDev

# Rust only (host build, no NDK):
cargo build -p share_nearby
```
