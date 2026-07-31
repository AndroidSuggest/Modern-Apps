package com.vayunmathur.library.image.fetchers

import android.content.Context

class ByteArrayFetcher : Fetcher {
    override suspend fun fetch(data: Any?, context: Context): FetchResult? {
        if (data is ByteArray) return FetchResult.Bytes(data)
        return null
    }
}
