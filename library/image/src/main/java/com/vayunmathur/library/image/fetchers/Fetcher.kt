package com.vayunmathur.library.image.fetchers

import android.content.Context

sealed class FetchResult {
    data class Bytes(val bytes: ByteArray, val isVideo: Boolean = false) : FetchResult()
    data class BitmapResult(val bitmap: android.graphics.Bitmap) : FetchResult()
}

interface Fetcher {
    suspend fun fetch(data: Any?, context: Context): FetchResult?
}
