package com.vayunmathur.library.image

import android.graphics.Bitmap

/**
 * Mirrors `coil.transform.Transformation` API shape.
 * [cacheKey] is included in memory/disk keys.
 */
interface Transformation {
    val cacheKey: String

    /**
     * Compatibility method – some call sites may prefer key() style.
     * Default implementation returns [cacheKey].
     */
    fun key(): String = cacheKey

    suspend fun transform(input: Bitmap, size: Size): Bitmap
}
