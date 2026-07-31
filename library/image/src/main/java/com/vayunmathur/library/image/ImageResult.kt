package com.vayunmathur.library.image

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable

/**
 * Result of [ImageLoader.execute]. Mirrors Coil's sealed result hierarchy minimally.
 */
sealed class ImageResult {
    data class Success(
        val bitmap: Bitmap,
        /** true if served from [MemoryCache] */
        val isFromMemory: Boolean = false,
        val dataSource: DataSource = DataSource.MEMORY,
    ) : ImageResult() {
        /** For TileCache compat – coil returned drawable, we re-expose as drawable. */
        val drawable: BitmapDrawable get() = BitmapDrawable(null as android.content.res.Resources?, bitmap).also {
            // Keep API similar – callers in old code casted: result as? SuccessResult then drawable as BitmapDrawable
        }

        fun asBitmapDrawable(resources: android.content.res.Resources? = null): BitmapDrawable =
            BitmapDrawable(resources, bitmap)
    }

    data class Error(
        val throwable: Throwable,
    ) : ImageResult()

    enum class DataSource { MEMORY, DISK, NETWORK, UNKNOWN }
}

// Backwards compat alias used in TileCache if needed – old: SuccessResult
typealias SuccessResult = ImageResult.Success
