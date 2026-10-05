package com.vayunmathur.library.image.util

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import java.io.File

private const val BYTES_PER_KB = 1024L
private const val BYTES_PER_MB = BYTES_PER_KB * BYTES_PER_KB
private const val MIN_MEMORY_CACHE_BYTES = 4 * 1024 * 1024
private const val MIN_DISK_CACHE_BYTES = 10L * 1024 * 1024
private const val FALLBACK_DISK_CACHE_BYTES = 50L * 1024 * 1024

fun Context.maxMemoryBytes(): Long {
    val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    return am.memoryClass * BYTES_PER_MB
}

fun memoryCacheMaxBytes(context: Context, percent: Double): Int {
    val max = context.maxMemoryBytes()
    return (max * percent).toInt().coerceAtLeast(MIN_MEMORY_CACHE_BYTES)
}

fun diskCacheMaxBytes(dir: File, percent: Double): Long {
    return try {
        val stat = StatFs(dir.absolutePath)
        val total = stat.blockCountLong * stat.blockSizeLong
        (total * percent).toLong().coerceAtLeast(MIN_DISK_CACHE_BYTES)
    } catch (_: Exception) {
        FALLBACK_DISK_CACHE_BYTES // 50MB fallback
    }
}

fun File.ensureExists(): File {
    if (!exists()) mkdirs()
    return this
}
