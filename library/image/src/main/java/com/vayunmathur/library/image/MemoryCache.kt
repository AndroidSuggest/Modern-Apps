package com.vayunmathur.library.image

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import com.vayunmathur.library.image.util.maxMemoryBytes

/**
 * Simple LRU memory cache counting [Bitmap.byteCount].
 * Mirrors coil.memory.MemoryCache.Builder(maxSizePercent) API shape.
 */
class MemoryCache internal constructor(
    private val lru: LruCache<String, Bitmap>,
) {
    fun get(key: String): Bitmap? = lru.get(key)

    fun put(key: String, bitmap: Bitmap) {
        lru.put(key, bitmap)
    }

    fun remove(key: String) = lru.remove(key)

    fun clear() = lru.evictAll()

    class Builder(private val context: Context) {
        private var maxSizePercent: Double = 0.25
        private var maxSizeBytes: Int? = null

        fun maxSizePercent(percent: Double): Builder {
            maxSizePercent = percent
            return this
        }

        /** Alias for Coil which exposed Float overload */
        fun maxSizePercent(percent: Float): Builder = maxSizePercent(percent.toDouble())

        fun maxSizeBytes(bytes: Int): Builder {
            maxSizeBytes = bytes
            return this
        }

        fun build(): MemoryCache {
            val maxBytes = maxSizeBytes ?: run {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val memClass = am.memoryClass * 1024 * 1024
                (memClass * maxSizePercent).toInt().coerceAtLeast(4 * 1024 * 1024)
            }

            val cache = object : LruCache<String, Bitmap>(maxBytes) {
                override fun sizeOf(key: String, value: Bitmap): Int {
                    return try {
                        value.byteCount
                    } catch (_: Exception) {
                        value.allocationByteCount
                    }
                }
            }
            return MemoryCache(cache)
        }
    }
}
