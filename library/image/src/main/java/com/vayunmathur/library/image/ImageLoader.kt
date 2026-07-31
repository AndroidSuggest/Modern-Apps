package com.vayunmathur.library.image

import android.content.Context
import android.graphics.Bitmap
import com.vayunmathur.library.image.decoders.BitmapDecoder
import com.vayunmathur.library.image.decoders.SvgDecoder
import com.vayunmathur.library.image.decoders.VideoFrameDecoder
import com.vayunmathur.library.image.fetchers.AssetFetcher
import com.vayunmathur.library.image.fetchers.BitmapFetcher
import com.vayunmathur.library.image.fetchers.ByteArrayFetcher
import com.vayunmathur.library.image.fetchers.ContentResolverFetcher
import com.vayunmathur.library.image.fetchers.FetchResult
import com.vayunmathur.library.image.fetchers.FileFetcher
import com.vayunmathur.library.image.fetchers.Fetcher
import com.vayunmathur.library.image.fetchers.HttpFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Replacement for `coil.ImageLoader`. Uses:
 * - [MemoryCache] (LruCache<String, Bitmap>)
 * - [DiskCache] (file LRU raw bytes)
 * - Fetcher registry (http/content/file/asset/bytearray/bitmap)
 * - Decoders: SVG via internal Android stdlib renderer (Canvas/Path), Video via MediaMetadataRetriever, Bitmap via BitmapFactory/ImageDecoder
 */
class ImageLoader private constructor(
    private val appContext: Context,
    val memoryCache: MemoryCache?,
    val diskCache: DiskCache?,
    private val fetchers: List<Fetcher>,
    private val respectCacheHeaders: Boolean = false,
) {

    companion object {
        @Volatile
        private var singleton: ImageLoader? = null

        fun get(context: Context): ImageLoader {
            return singleton ?: synchronized(this) {
                singleton ?: Builder(context.applicationContext).build().also { singleton = it }
            }
        }

        internal fun setDefault(loader: ImageLoader) {
            singleton = loader
        }
    }

    private fun computeCacheKey(request: ImageRequest): String {
        request.memoryCacheKey?.let { return it }
        request.diskCacheKey?.let { return it }
        val dataStr = when (val d = request.data) {
            null -> "null"
            is String -> d
            is ByteArray -> "bytes_${d.size}_${d.take(16).hashCode()}"
            is Bitmap -> "bitmap_${d.width}x${d.height}_${d.hashCode()}"
            else -> d.toString()
        }
        val transKey = request.transformations.joinToString("|") { it.cacheKey }
        val sizeKey = request.size?.let { "${it.width}x${it.height}" } ?: "orig"
        val videoKey = request.videoFrameMillis?.let { "vf_$it" } ?: ""
        return "$dataStr|$sizeKey|$transKey|$videoKey"
    }

    suspend fun execute(request: ImageRequest): ImageResult = withContext(Dispatchers.IO) {
        val context = request.context ?: appContext

        if (request.data is Bitmap) {
            val bmp = request.data
            val transformed = applyTransformations(bmp, request)
            return@withContext ImageResult.Success(transformed, isFromMemory = false, dataSource = ImageResult.DataSource.MEMORY)
        }

        val cacheKey = computeCacheKey(request)
        val diskKey = request.diskCacheKey ?: cacheKey

        try {
            memoryCache?.get(cacheKey)?.let { cached ->
                return@withContext ImageResult.Success(cached, isFromMemory = true, dataSource = ImageResult.DataSource.MEMORY)
            }
        } catch (_: Exception) {}

        var diskBytes: ByteArray? = null
        if (diskCache != null) {
            try {
                diskBytes = diskCache.get(diskKey)
                if (diskBytes != null) {
                    val decodedFromDisk = decodeBytes(diskBytes, request, context)
                    if (decodedFromDisk != null) {
                        val transformed = applyTransformations(decodedFromDisk, request)
                        try { memoryCache?.put(cacheKey, transformed) } catch (_: Exception) {}
                        return@withContext ImageResult.Success(transformed, isFromMemory = false, dataSource = ImageResult.DataSource.DISK)
                    }
                }
            } catch (_: Exception) {}
        }

        if (request.videoFrameMillis != null) {
            try {
                val videoBmp = VideoFrameDecoder.decode(request, context)
                if (videoBmp != null) {
                    val transformed = applyTransformations(videoBmp, request)
                    try { memoryCache?.put(cacheKey, transformed) } catch (_: Exception) {}
                    return@withContext ImageResult.Success(transformed, isFromMemory = false, dataSource = ImageResult.DataSource.MEMORY)
                }
            } catch (_: Exception) {}
        }

        var fetchedBytes: ByteArray? = null
        try {
            for (fetcher in fetchers) {
                val result = try { fetcher.fetch(request.data, context) } catch (_: Exception) { null }
                if (result != null) {
                    when (result) {
                        is FetchResult.BitmapResult -> {
                            val transformed = applyTransformations(result.bitmap, request)
                            try { memoryCache?.put(cacheKey, transformed) } catch (_: Exception) {}
                            return@withContext ImageResult.Success(transformed, isFromMemory = false, dataSource = ImageResult.DataSource.MEMORY)
                        }
                        is FetchResult.Bytes -> {
                            fetchedBytes = result.bytes
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            return@withContext ImageResult.Error(e)
        }

        if (fetchedBytes == null) {
            fetchedBytes = diskBytes
            if (fetchedBytes == null) {
                return@withContext ImageResult.Error(IllegalArgumentException("Unable to fetch data: ${request.data}"))
            }
        }

        val decoded = try {
            decodeBytes(fetchedBytes, request, context) ?: if (request.videoFrameMillis != null) {
                VideoFrameDecoder.decode(request, context)
            } else null
        } catch (e: Exception) {
            return@withContext ImageResult.Error(e)
        }

        if (decoded == null) {
            return@withContext ImageResult.Error(IllegalArgumentException("Failed to decode image bytes (${fetchedBytes.size} bytes)"))
        }

        val transformed = applyTransformations(decoded, request)

        try { memoryCache?.put(cacheKey, transformed) } catch (_: Exception) {}
        try {
            if (diskCache != null) {
                diskCache.put(diskKey, fetchedBytes)
            }
        } catch (_: Exception) {}

        return@withContext ImageResult.Success(transformed, isFromMemory = false, dataSource = ImageResult.DataSource.NETWORK)
    }

    private suspend fun decodeBytes(bytes: ByteArray, request: ImageRequest, context: Context): Bitmap? {
        if (SvgDecoder.canDecode(bytes, request.data)) {
            val svgBmp = SvgDecoder.decode(bytes, request)
            if (svgBmp != null) return svgBmp
        }
        return BitmapDecoder.decode(bytes, request, request.allowHardware)
    }

    private suspend fun applyTransformations(bitmap: Bitmap, request: ImageRequest): Bitmap {
        var current = bitmap
        for (t in request.transformations) {
            try {
                current = t.transform(current, request.size ?: Size.Original)
            } catch (_: Exception) {}
        }
        return current
    }

    class Builder(private val context: Context) {
        private var memoryCacheInstance: MemoryCache? = null
        private var diskCacheInstance: DiskCache? = null
        private var respectCacheHeaders: Boolean = true
        private val extraFetchers: MutableList<Fetcher> = mutableListOf()

        inner class ComponentsBuilder {
            fun add(factory: Any): ComponentsBuilder = this
        }

        fun components(block: ComponentsBuilder.() -> Unit): Builder {
            val cb = ComponentsBuilder()
            cb.block()
            return this
        }

        fun memoryCache(cache: MemoryCache): Builder {
            memoryCacheInstance = cache
            return this
        }

        fun memoryCache(block: () -> MemoryCache): Builder {
            memoryCacheInstance = block()
            return this
        }

        fun diskCache(cache: DiskCache): Builder {
            diskCacheInstance = cache
            return this
        }

        fun diskCache(block: () -> DiskCache): Builder {
            diskCacheInstance = block()
            return this
        }

        fun respectCacheHeaders(respect: Boolean): Builder {
            respectCacheHeaders = respect
            return this
        }

        fun addFetcher(fetcher: Fetcher): Builder {
            extraFetchers += fetcher
            return this
        }

        fun build(): ImageLoader {
            val mem = memoryCacheInstance ?: MemoryCache.Builder(context.applicationContext).build()
            val disk = diskCacheInstance ?: try {
                val dir = context.applicationContext.cacheDir.resolve("image_cache")
                DiskCache.Builder().directory(dir).maxSizePercent(0.05).build()
            } catch (_: Exception) { null }

            val defaultFetchers = listOf(
                BitmapFetcher(),
                ByteArrayFetcher(),
                FileFetcher(),
                AssetFetcher(),
                ContentResolverFetcher(),
                HttpFetcher(),
            )
            val allFetchers = extraFetchers + defaultFetchers

            return ImageLoader(
                appContext = context.applicationContext,
                memoryCache = mem,
                diskCache = disk,
                fetchers = allFetchers,
                respectCacheHeaders = respectCacheHeaders,
            )
        }
    }
}
