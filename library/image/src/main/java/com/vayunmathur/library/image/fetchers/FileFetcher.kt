package com.vayunmathur.library.image.fetchers

import android.content.Context
import android.net.Uri
import java.io.File

class FileFetcher : Fetcher {
    override suspend fun fetch(data: Any?, context: Context): FetchResult? {
        when (data) {
            is File -> {
                if (!data.exists()) return null
                return try {
                    FetchResult.Bytes(data.readBytes())
                } catch (_: Exception) { null }
            }
            is Uri -> {
                if (data.scheme == "file") {
                    val path = data.path ?: return null
                    val f = File(path)
                    if (!f.exists()) return null
                    return try {
                        FetchResult.Bytes(f.readBytes())
                    } catch (_: Exception) { null }
                }
            }
            is String -> {
                if (data.startsWith("file://")) {
                    val path = data.removePrefix("file://")
                    val f = File(path)
                    if (!f.exists()) return null
                    return try {
                        FetchResult.Bytes(f.readBytes())
                    } catch (_: Exception) { null }
                }
                // plain path?
                if (data.startsWith("/") ) {
                    val f = File(data)
                    if (f.exists()) {
                        return try { FetchResult.Bytes(f.readBytes()) } catch (_: Exception) { null }
                    }
                }
            }
        }
        return null
    }
}
