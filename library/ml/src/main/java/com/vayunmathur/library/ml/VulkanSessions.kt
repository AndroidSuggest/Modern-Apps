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

    fun isUsable(): Boolean = (probe and 1) != 0 && (probe and 2) != 0

    fun open(key: String, readModel: () -> ByteArray, baseDir: String?): Long {
        return try {
            val model = readModel()
            val handle = VulkanBridge.load(model, baseDir)
            if (handle != 0L) {
                synchronized(lock) {
                    allowlist.add(key)
                }
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

    /**
     * Validate [readModel]'s bytes without creating a session. Null means clear.
     *
     * [readModel] is last so call sites use trailing-lambda form
     * (`preflight(key) { modelBytes }`).
     */
    fun preflight(key: String, baseDir: String? = null, readModel: () -> ByteArray): String? {
        return try {
            val model = readModel()
            VulkanBridge.loadPreflight(model, baseDir)
        } catch (t: Throwable) {
            Log.e(TAG, "Vulkan preflight failed for $key", t)
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
