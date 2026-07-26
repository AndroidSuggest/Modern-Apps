package com.vayunmathur.maps.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.maplibre.android.module.http.HttpRequestUtil
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * Disk cache for the streamed protomaps basemap tiles.
 *
 * The basemap is streamed live from [BASEMAP_PMTILES_URL] via pmtiles-over-HTTP
 * range requests. MapLibre routes every HTTP resource load through the OkHttp
 * client installed via [HttpRequestUtil.setOkHttpClient]; this interceptor sits
 * on that client and caches the range responses for the pmtiles host.
 *
 * Cache policy (per requested byte range = one tile/directory/header chunk):
 *  - A cached range is kept on disk indefinitely and served whenever the device
 *    is offline, so previously-viewed areas keep working with no network.
 *  - A range is only re-fetched when the device is online AND it is next
 *    requested at least [REFRESH_INTERVAL_MS] after it was last fetched.
 *  - A cached range is never evicted for being stale; it is only overwritten on
 *    a *successful* online refetch. A failed refetch falls back to the cache.
 */
object MapTileCache {
    const val BASEMAP_PMTILES_URL =
        "pmtiles://https://demo-bucket.protomaps.com/v4.pmtiles"

    private const val TILE_HOST = "demo-bucket.protomaps.com"
    private val REFRESH_INTERVAL_MS = TimeUnit.HOURS.toMillis(24)

    @Volatile private var installed = false

    /**
     * Install the caching OkHttp client into MapLibre. Idempotent; must run
     * before the first map request (i.e. before the map composable is created).
     */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        val appContext = context.applicationContext
        // Prefer external files (excluded from the 25 MB cloud-backup quota,
        // like the downloaded zone pmtiles) and fall back to internal files.
        val root = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        val cacheDir = File(root, CACHE_DIR_NAME).apply { mkdirs() }
        val client = OkHttpClient.Builder()
            .addInterceptor(TileCacheInterceptor(appContext, cacheDir))
            .build()
        HttpRequestUtil.setOkHttpClient(client)
        installed = true
    }

    internal const val CACHE_DIR_NAME = "tilecache"

    private class TileCacheInterceptor(
        private val context: Context,
        private val cacheDir: File,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (request.method != "GET" || request.url.host != TILE_HOST) {
                return chain.proceed(request)
            }

            val key = keyFor(request.url.toString(), request.header("Range"))
            val dataFile = File(cacheDir, "$key.data")
            val metaFile = File(cacheDir, "$key.meta")
            val cached = dataFile.exists() && metaFile.exists()
            val fresh = cached &&
                System.currentTimeMillis() - dataFile.lastModified() < REFRESH_INTERVAL_MS

            // Serve from cache without touching the network when the entry is
            // still fresh, or whenever we're offline (stale-but-usable).
            if (cached && (fresh || !isOnline())) {
                return buildFromCache(request, dataFile, metaFile)
            }

            val networkResponse = try {
                chain.proceed(request)
            } catch (e: IOException) {
                // Network error (e.g. went offline mid-session): fall back to
                // the stale cache if we have it, otherwise propagate.
                if (cached) return buildFromCache(request, dataFile, metaFile)
                throw e
            }

            if (!networkResponse.isSuccessful) {
                // Server error / 304: keep serving the existing cache rather
                // than replacing it.
                if (cached) {
                    networkResponse.close()
                    return buildFromCache(request, dataFile, metaFile)
                }
                return networkResponse
            }

            val contentType = networkResponse.header("Content-Type")
            val contentRange = networkResponse.header("Content-Range")
            val acceptRanges = networkResponse.header("Accept-Ranges")
            val code = networkResponse.code
            val message = networkResponse.message
            val bytes = networkResponse.body?.bytes() ?: ByteArray(0)

            writeCache(dataFile, metaFile, code, message, contentType, contentRange, acceptRanges, bytes)

            return networkResponse.newBuilder()
                .body(bytes.toResponseBody(contentType?.toMediaTypeOrNull()))
                .build()
        }

        private fun buildFromCache(request: okhttp3.Request, dataFile: File, metaFile: File): Response {
            val props = Properties()
            metaFile.inputStream().use { props.load(it) }
            val bytes = dataFile.readBytes()
            val contentType = props.getProperty(KEY_CONTENT_TYPE)
            val builder = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(props.getProperty(KEY_CODE, "200").toIntOrNull() ?: 200)
                .message(props.getProperty(KEY_MESSAGE, "OK"))
                .body(bytes.toResponseBody(contentType?.toMediaTypeOrNull()))
            contentType?.let { builder.header("Content-Type", it) }
            builder.header("Content-Length", bytes.size.toString())
            props.getProperty(KEY_CONTENT_RANGE)?.let { builder.header("Content-Range", it) }
            props.getProperty(KEY_ACCEPT_RANGES)?.let { builder.header("Accept-Ranges", it) }
            return builder.build()
        }

        private fun writeCache(
            dataFile: File,
            metaFile: File,
            code: Int,
            message: String,
            contentType: String?,
            contentRange: String?,
            acceptRanges: String?,
            bytes: ByteArray,
        ) {
            try {
                val props = Properties().apply {
                    setProperty(KEY_CODE, code.toString())
                    setProperty(KEY_MESSAGE, message.ifEmpty { "OK" })
                    contentType?.let { setProperty(KEY_CONTENT_TYPE, it) }
                    contentRange?.let { setProperty(KEY_CONTENT_RANGE, it) }
                    acceptRanges?.let { setProperty(KEY_ACCEPT_RANGES, it) }
                }
                // Write meta first, then data, each via temp+rename so a reader
                // never sees a half-written file. Presence of the data file then
                // implies the meta file is already in place.
                val metaTmp = File.createTempFile("m", null, cacheDir)
                metaTmp.outputStream().use { props.store(it, null) }
                metaTmp.renameTo(metaFile)

                val dataTmp = File.createTempFile("d", null, cacheDir)
                dataTmp.outputStream().use { it.write(bytes) }
                dataTmp.renameTo(dataFile)
                dataFile.setLastModified(System.currentTimeMillis())
            } catch (_: IOException) {
                // Caching is best-effort; a write failure must not break the map.
            }
        }

        private fun isOnline(): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        private fun keyFor(url: String, range: String?): String {
            val raw = url + "\n" + (range ?: "")
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }

    private const val KEY_CODE = "code"
    private const val KEY_MESSAGE = "message"
    private const val KEY_CONTENT_TYPE = "contentType"
    private const val KEY_CONTENT_RANGE = "contentRange"
    private const val KEY_ACCEPT_RANGES = "acceptRanges"
}
