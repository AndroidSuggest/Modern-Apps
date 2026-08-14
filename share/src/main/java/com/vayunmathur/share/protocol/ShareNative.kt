package com.vayunmathur.share.protocol

/**
 * JNI surface for the native Nearby Share / Quick Share protocol crate
 * (libshare_nearby.so). Kotlin owns transport (NSD/BLE discovery + TCP);
 * Rust owns the pure state machine (UKEY2, protobuf framing, secure messages,
 * introduction/payload). No networking touches Rust.
 *
 * Threading: all calls are synchronous and serialized per session handle.
 * Call from a single coroutine / background thread per session; the Rust side
 * guards sessions with a Mutex but the caller should still serialize feed +
 * drain per session.
 *
 * Lifecycle (see share/PROTOCOL_CONTRACT.md for the full spec):
 *   handle = nativeInit(localName, localEndpointInfo)
 *   repeat { if ((bytes = socket.read()) != null) nativeFeedInbound(handle, bytes) }
 *   while ((out = nativeDrainOutbound(handle)) != null) socket.write(out)
 *   state = nativeQueryState(handle); files = nativeQueryPendingFiles(handle)
 *   nativeAccept(handle, accept, destDir)  // after user taps Accept/Reject
 *   // During Transferring: nativeOpenFile / nativeWriteChunk / nativeCloseFile
 *   nativeDestroy(handle) // always, e.g. in finally / onCleared
 */
internal object ShareNative {
    external fun nativeInit(localName: String, localEndpointInfo: ByteArray): Long
    external fun nativeFeedInbound(handle: Long, bytes: ByteArray): Int
    external fun nativeDrainOutbound(handle: Long): ByteArray?
    external fun nativeQueryState(handle: Long): Int
    external fun nativeQueryPendingFiles(handle: Long): ByteArray?
    external fun nativeAccept(handle: Long, accept: Boolean, destDir: String): Int
    external fun nativeOpenFile(handle: Long, fileName: String, fileSize: Long): Int
    external fun nativeWriteChunk(handle: Long, chunk: ByteArray): Int
    external fun nativeCloseFile(handle: Long): Int
    external fun nativeDestroy(handle: Long)

    init {
        System.loadLibrary("share_nearby")
    }
}
