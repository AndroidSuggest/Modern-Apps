package com.vayunmathur.library.image.fetchers

import android.content.Context
import android.net.Uri
import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HttpFetcher : Fetcher {
    override suspend fun fetch(data: Any?, context: Context): FetchResult? {
        val url: String = when (data) {
            is String -> data
            is Uri -> {
                val s = data.scheme
                if (s == "http" || s == "https") data.toString() else return null
            }
            else -> return null
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null

        return withContext(Dispatchers.IO) {
            try {
                val resp = NetworkClient.execute(url)
                if (!resp.isSuccess) throw java.io.IOException("HTTP ${resp.status} for $url")
                FetchResult.Bytes(resp.bytes)
            } catch (e: Exception) {
                throw e
            }
        }
    }
}
