package com.vayunmathur.library.image.fetchers

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContentResolverFetcher : Fetcher {
    override suspend fun fetch(data: Any?, context: Context): FetchResult? {
        val uri: Uri = when (data) {
            is Uri -> data
            is String -> try { data.toUri() } catch (_: Exception) { return null }
            else -> return null
        }
        if (uri.scheme != "content") return null
        return withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let {
                    FetchResult.Bytes(it)
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
