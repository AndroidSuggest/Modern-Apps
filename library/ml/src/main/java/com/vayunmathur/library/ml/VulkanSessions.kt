// PACKAGE STRUCTURE EXCEPTION (JNI)
package com.vayunmathur.library.ml

import android.util.Log

internal object VulkanSessions {
    private const val TAG = "VulkanSessions"

    @Volatile
    var probe: Int = 0

    private val lock = Any()

    /**
     * Model keys cleared for Vulkan. Gated per call site: handles check
     * `key in allowlist` before opening so an unvetted graph never reaches
     * the driver. [open] adds a key on success.
     */
    internal val allowlist = mutableSetOf("asset:maia3-5m.onnx")

    /** Populate [probe] once from the native device probe; safe to call repeatedly. */
    fun ensureProbed() {
        if (probe != 0) return
        probe = try {
            VulkanBridge.probe()
        } catch (t: Throwable) {
            Log.e(TAG, "Vulkan probe failed", t)
            0
        }
    }

    fun isUsable(): Boolean {
        ensureProbed()
        return (probe and 1) != 0 && (probe and 2) != 0
    }

    /** Load model bytes onto the GPU. Returns a handle, or 0 on failure. */
    fun open(key: String, readModel: () -> ByteArray): Long {
        return try {
            val handle = VulkanBridge.load(readModel(), key)
            if (handle != 0L) {
                synchronized(lock) { allowlist.add(key) }
                Log.i(TAG, "Vulkan session open for $key")
            } else {
                Log.e(TAG, "cannot open Vulkan session for $key")
            }
            handle
        } catch (t: Throwable) {
            Log.e(TAG, "cannot open Vulkan session for $key", t)
            0L
        }
    }

    /** Load a model by path (resolves sidecar .onnx_data). Returns a handle, or 0. */
    fun openPath(key: String, path: String): Long {
        return try {
            val handle = VulkanBridge.loadPath(path)
            if (handle != 0L) {
                synchronized(lock) { allowlist.add(key) }
                Log.i(TAG, "Vulkan session open for $key at $path")
            } else {
                Log.e(TAG, "cannot open Vulkan session for $key at $path")
            }
            handle
        } catch (t: Throwable) {
            Log.e(TAG, "cannot open Vulkan session for $key at $path", t)
            0L
        }
    }

    /**
     * Validate [readModel]'s bytes without keeping a session. Null means the
     * model would load on the GPU; a non-null string is the native refusal
     * reason (log it to learn why a model stays on the CPU path).
     */
    fun preflight(key: String, readModel: () -> ByteArray): String? {
        return try {
            val reason = VulkanBridge.loadPreflight(readModel())
            if (reason != null) Log.i(TAG, "Vulkan preflight refused $key: $reason")
            reason
        } catch (t: Throwable) {
            Log.e(TAG, "Vulkan preflight failed for $key", t)
            "preflight threw: ${t.message}"
        }
    }

    /**
     * Run inference on [handle] with [inputs], returning output tensors or null
     * on any failure (caller falls back to CPU). Caller must hold its own lock
     * across the call, as native sessions are not thread-safe.
     */
    fun run(handle: Long, inputs: List<VulkanTensor>): List<VulkanTensor>? {
        if (handle == 0L) return null
        return try {
            val out = VulkanBridge.run(handle, VulkanWire.encode(inputs)) ?: run {
                val reason = try {
                    VulkanBridge.lastError(handle)
                } catch (t: Throwable) {
                    null
                }
                Log.w(TAG, "vulkan run null for handle=$handle: $reason")
                return null
            }
            VulkanWire.decode(out)
        } catch (t: Throwable) {
            Log.e(TAG, "Vulkan run failed on $handle", t)
            null
        }
    }

    fun close(handle: Long) {
        if (handle == 0L) return
        try {
            VulkanBridge.close(handle)
        } catch (t: Throwable) {
            Log.e(TAG, "cannot close Vulkan session $handle", t)
        }
    }
}
