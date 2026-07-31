package com.vayunmathur.library.image

import android.content.Context
import com.vayunmathur.library.image.util.ensureExists
import com.vayunmathur.library.image.util.sha256
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Very small file LRU disk cache.
 * - Key is hashed via SHA-256 to form a filename.
 * - Raw bytes are stored (encoded image bytes, e.g. JPEG/PNG).
 * - Eviction: oldest lastModified first when over size limit.
 *
 * Mirrors Coil diskCache { directory(cacheDir/image_cache).maxSizePercent(0.05) } shape.
 */
class DiskCache internal constructor(
    private val directory: File,
    private val maxSizeBytes: Long,
) {
    private val lock = ReentrantLock()

    init {
        directory.ensureExists()
    }

    fun get(key: String): ByteArray? = lock.withLock {
        val file = fileFor(key)
        if (!file.exists()) return null
        try {
            file.setLastModified(System.currentTimeMillis())
            file.readBytes()
        } catch (_: Exception) {
            null
        }
    }

    fun put(key: String, bytes: ByteArray) {
        lock.withLock {
            try {
                directory.ensureExists()
                val file = fileFor(key)
                // atomic write via temp
                val tmp = File(directory, "${file.name}.tmp_${System.nanoTime()}")
                tmp.writeBytes(bytes)
                if (file.exists()) file.delete()
                tmp.renameTo(file)
                trimIfNeededLocked()
            } catch (_: Exception) {
            }
        }
    }

    fun remove(key: String) = lock.withLock {
        try { fileFor(key).delete() } catch (_: Exception) {}
    }

    fun clear() = lock.withLock {
        try { directory.listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
    }

    private fun fileFor(key: String): File = File(directory, key.sha256())

    private fun trimIfNeededLocked() {
        try {
            val files = directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: return
            var total = files.sumOf { it.length() }
            if (total <= maxSizeBytes) return
            // oldest first
            val sorted = files.sortedBy { it.lastModified() }
            for (f in sorted) {
                if (total <= maxSizeBytes) break
                val len = f.length()
                if (f.delete()) total -= len
            }
        } catch (_: Exception) {
        }
    }

    class Builder {
        private var directory: File? = null
        private var maxSizePercent: Double = 0.02
        private var maxSizeBytes: Long? = null

        fun directory(dir: File): Builder {
            directory = dir
            return this
        }

        fun maxSizePercent(percent: Double): Builder {
            maxSizePercent = percent
            return this
        }

        fun maxSizePercent(percent: Float): Builder = maxSizePercent(percent.toDouble())

        /** Optional fixed size */
        fun maxSizeBytes(bytes: Long): Builder {
            maxSizeBytes = bytes
            return this
        }

        fun build(): DiskCache {
            val dir = directory ?: throw IllegalStateException("DiskCache directory not set")
            val maxBytes = maxSizeBytes ?: run {
                try {
                    val stat = android.os.StatFs(dir.absolutePath)
                    val total = stat.blockCountLong * stat.blockSizeLong
                    (total * maxSizePercent).toLong().coerceAtLeast(10L * 1024 * 1024)
                } catch (_: Exception) {
                    50L * 1024 * 1024
                }
            }
            return DiskCache(dir, maxBytes)
        }
    }
}
