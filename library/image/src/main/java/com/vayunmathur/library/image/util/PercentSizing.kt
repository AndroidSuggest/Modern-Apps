package com.vayunmathur.library.image.util

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import java.io.File

fun Context.maxMemoryBytes(): Long {
    val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    return (am.memoryClass * 1024L * 1024L)
}

fun memoryCacheMaxBytes(context: Context, percent: Double): Int {
    val max = context.maxMemoryBytes()
    return (max * percent).toInt().coerceAtLeast(4 * 1024 * 1024)
}

fun diskCacheMaxBytes(dir: File, percent: Double): Long {
    return try {
        val stat = StatFs(dir.absolutePath)
        val total = stat.blockCountLong * stat.blockSizeLong
        (total * percent).toLong().coerceAtLeast(10L * 1024 * 1024)
    } catch (_: Exception) {
        (50L * 1024 * 1024) // 50MB fallback
    }
}

fun File.ensureExists(): File {
    if (!exists()) mkdirs()
    return this
}
