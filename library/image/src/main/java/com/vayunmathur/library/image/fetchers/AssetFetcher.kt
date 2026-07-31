package com.vayunmathur.library.image.fetchers

import android.content.Context

/**
 * Handles `file:///android_asset/` URLs by reading from [Context.assets].
 * Also handles plain asset paths starting with `android_asset/`.
 */
class AssetFetcher : Fetcher {
    override suspend fun fetch(data: Any?, context: Context): FetchResult? {
        val path = when (data) {
            is String -> {
                when {
                    data.startsWith("file:///android_asset/") -> data.removePrefix("file:///android_asset/")
                    data.startsWith("/android_asset/") -> data.removePrefix("/android_asset/")
                    data.startsWith("android_asset/") -> data.removePrefix("android_asset/")
                    else -> return null
                }
            }
            else -> return null
        }
        if (path.isBlank()) return null
        return try {
            context.assets.open(path).use { it.readBytes() }.let { FetchResult.Bytes(it) }
        } catch (_: Exception) {
            null
        }
    }
}
