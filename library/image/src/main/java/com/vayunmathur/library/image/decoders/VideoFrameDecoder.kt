package com.vayunmathur.library.image.decoders

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.vayunmathur.library.image.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object VideoFrameDecoder {

    suspend fun decode(
        request: ImageRequest,
        context: Context?,
        fetchedBytesFallback: ByteArray? = null,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val millis = request.videoFrameMillis ?: return@withContext null
        val ctx = context ?: request.context ?: return@withContext null
        val data = request.data ?: return@withContext null

        try {
            val retriever = MediaMetadataRetriever()
            try {
                when (data) {
                    is Uri -> {
                        try {
                            retriever.setDataSource(ctx, data)
                        } catch (_: Exception) {
                            // try with path
                            val p = data.path ?: return@withContext null
                            retriever.setDataSource(p)
                        }
                    }
                    is String -> {
                        val uri = try { Uri.parse(data) } catch (_: Exception) { null }
                        when {
                            uri != null && uri.scheme == "content" -> retriever.setDataSource(ctx, uri)
                            data.startsWith("/") || data.startsWith("file://") -> {
                                val path = if (data.startsWith("file://")) data.removePrefix("file://") else data
                                retriever.setDataSource(path)
                            }
                            data.startsWith("http://") || data.startsWith("https://") -> {
                                // MediaMetadataRetriever supports remote URL – supply empty headers
                                retriever.setDataSource(data, HashMap())
                            }
                            else -> {
                                val f = File(data)
                                if (f.exists()) retriever.setDataSource(f.absolutePath)
                                else return@withContext null
                            }
                        }
                    }
                    is File -> retriever.setDataSource(data.absolutePath)
                    else -> return@withContext null
                }

                val timeUs = millis * 1000L
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            null
        }
    }
}
