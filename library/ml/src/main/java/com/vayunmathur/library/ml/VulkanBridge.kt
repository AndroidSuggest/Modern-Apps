// PACKAGE STRUCTURE EXCEPTION (JNI)
package com.vayunmathur.library.ml

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

    external fun probe(): Int

    external fun load(model: ByteArray, baseDir: String?): Long

    external fun loadPreflight(model: ByteArray, baseDir: String?): String?

    external fun loadPath(path: String): Long

    external fun close(handle: Long)

    external fun run(
        handle: Long,
        names: Array<String>,
        dtypes: IntArray,
        shapes: LongArray,
        offsets: LongArray,
        payload: ByteArray,
    ): ByteArray?

    /**
     * [run] with per-input shape offsets as [IntArray].
     *
     * Compatibility shim: several handles were written against an IntArray-offsets
     * draft of this API. Converts and delegates to the JNI function above.
     */
    fun run(
        handle: Long,
        names: Array<String>,
        dtypes: IntArray,
        shapes: LongArray,
        offsets: IntArray,
        payload: ByteArray,
    ): ByteArray? =
        run(handle, names, dtypes, shapes, offsets.map { it.toLong() }.toLongArray(), payload)

    external fun lastOutputShapes(handle: Long): LongArray?

    external fun lastOutputNames(handle: Long): Array<String>?

    external fun kvCreate(handle: Long, maxSeq: Int): Long

    external fun kvClose(kv: Long)

    external fun runCached(
        handle: Long,
        kv: Long,
        names: Array<String>,
        dtypes: IntArray,
        shapes: LongArray,
        offsets: LongArray,
        payload: ByteArray,
    ): ByteArray?

    /**
     * [runCached] with per-input shape offsets as [IntArray].
     *
     * Compatibility shim, same as [run]: converts and delegates.
     */
    fun runCached(
        handle: Long,
        kv: Long,
        names: Array<String>,
        dtypes: IntArray,
        shapes: LongArray,
        offsets: IntArray,
        payload: ByteArray,
    ): ByteArray? =
        runCached(handle, kv, names, dtypes, shapes, offsets.map { it.toLong() }.toLongArray(), payload)

    external fun argmaxLastRow(
        payload: ByteArray,
        seqLen: Int,
        vocab: Int,
        suppress: IntArray,
    ): Int
}
