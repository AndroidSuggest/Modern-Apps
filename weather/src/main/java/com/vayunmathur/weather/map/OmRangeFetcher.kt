package com.vayunmathur.weather.map

import java.net.HttpURLConnection
import java.net.URL

/**
 * JVM callback used by Rust `JvmRangeBackend` (libweather_om.so) to fetch
 * file size and arbitrary byte ranges via HttpURLConnection.
 *
 * This replaces the previous `ureq` crate in Rust which pulled ~90 crates
 * (ring/rustls/icu/url). The map now fetches only the 64KB blocks that the
 * `.om` decoder actually needs (~few MB per view) instead of the full
 * 148 MB file that caused OOM crashes when fetched via
 * `NetworkClient.performRequestBytes`.
 */
object OmRangeFetcher {

    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 30_000

    @JvmStatic
    fun getFileSize(url: String): Long {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=0-0")
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                instanceFollowRedirects = true
                useCaches = false
            }
            // Trigger
            val code = conn.responseCode
            val total = conn.getHeaderField("Content-Range")
                ?.substringAfterLast('/')
                ?.toLongOrNull()
                ?: conn.getHeaderField("Content-Length")?.toLongOrNull()
                ?: conn.contentLengthLong.takeIf { it > 0 }
                ?: when (code) {
                    200 -> conn.contentLengthLong.takeIf { it > 0 }
                    206 -> {
                        // Content-Range: bytes 0-0/155373816
                        conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    }
                    else -> null
                }
            conn.disconnect()
            total ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    @JvmStatic
    fun fetchRange(url: String, offset: Long, length: Long): ByteArray? {
        if (length <= 0) return ByteArray(0)
        return try {
            val end = offset + length - 1
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=$offset-$end")
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                instanceFollowRedirects = true
                useCaches = false
            }
            val code = conn.responseCode
            if (code != 206 && code != 200) {
                conn.disconnect()
                return null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            // Server may return more than requested when it ignores Range (200).
            // Slice to the exact requested slice if we have size info.
            if (code == 200 && bytes.size > length && offset == 0L) {
                // It's a full file response (Range ignored). Return as-is only if caller
                // expects full probe (handled above). For range path we still accept but
                // slice from offset.
                bytes.copyOfRange(offset.toInt(), (offset + length).toInt().coerceAtMost(bytes.size))
            } else {
                bytes
            }
        } catch (_: Exception) {
            null
        }
    }
}
