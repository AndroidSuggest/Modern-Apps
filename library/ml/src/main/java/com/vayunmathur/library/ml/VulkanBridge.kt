// PACKAGE STRUCTURE EXCEPTION (JNI)
package com.vayunmathur.library.ml

/**
 * Raw JNI surface for the `ml_vulkan` native library.
 *
 * Every declaration here mirrors an `extern "system"` function in
 * `library/ml/src/main/rust/src/jni_bridge.rs` exactly — same name, same
 * argument count, same types. The JVM binds native methods by short name and
 * passes arguments positionally, so any drift between these signatures and the
 * Rust exports silently corrupts the call (a `String[]` gets read as a byte
 * buffer, etc.). Do not add or reorder parameters without changing the Rust
 * side in the same commit.
 *
 * Inference marshalling uses the self-describing `MLV1` payload produced by
 * [VulkanWire]; the flat `ByteArray` carries every tensor's dtype and shape, so
 * no side-channel descriptor arrays cross JNI.
 */
internal object VulkanBridge {
    init {
        try {
            System.loadLibrary("ml_vulkan")
        } catch (_: UnsatisfiedLinkError) {
            // Native library absent (non-ARM64, unit tests, or Rust module not yet built).
            // Callers must gate on VulkanSessions.isUsable(); every extern below will throw
            // UnsatisfiedLinkError if reached without the library, which VulkanSessions swallows.
        }
    }

    /** Device capability bitmask: 1=loader, 2=compute, 4=coopmat16, 8=vulkan1.2. */
    external fun probe(): Int

    /** Load model bytes; `tag` is an opaque diagnostic label. Returns handle or 0. */
    external fun load(model: ByteArray, tag: String): Long

    /** Attempt a real load and return the refusal reason, or null if it would load. */
    external fun loadPreflight(model: ByteArray): String?

    /** Load a model from a filesystem path (resolves sidecar .onnx_data). Handle or 0. */
    external fun loadPath(path: String): Long

    /** Release a session handle. */
    external fun close(handle: Long)

    /** Run inference over an MLV1 input payload, returning an MLV1 output payload or null. */
    external fun run(handle: Long, flatInput: ByteArray): ByteArray?

    /** Most recent run/runCached failure reason for this handle, or null. Consumes it. */
    external fun lastError(handle: Long): String?

    /** Last inference output shapes as JSON, or null. */
    external fun lastOutputShapes(handle: Long): String?

    /** Last inference output names as JSON, or null. */
    external fun lastOutputNames(handle: Long): String?

    /** Create a KV cache for autoregressive decoding. Returns cache handle or 0. */
    external fun kvCreate(handle: Long, maxTokens: Int): Long

    /** Release a KV cache handle. */
    external fun kvClose(kv: Long)

    /** Run one cached decode step over an MLV1 input payload. Returns MLV1 output or null. */
    external fun runCached(handle: Long, kv: Long, flatInput: ByteArray): ByteArray?

    /** Argmax over the last row of little-endian f32 logits with `cols` columns, or -1. */
    external fun argmaxLastRow(logits: ByteArray, cols: Int): Int
}
