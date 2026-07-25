package com.vayunmathur.library.map

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Identifies a single XYZ tile. */
internal data class TileKey(val z: Int, val x: Int, val y: Int)

/**
 * Fetches raster tiles through a Coil [ImageLoader] (which handles memory + disk
 * caching) and holds decoded [ImageBitmap]s in a snapshot-state map so tile
 * arrival recomposes the map canvas. In-flight keys are tracked to avoid
 * duplicate requests.
 */
internal class TileCache(
    private val context: Context,
    private val loader: ImageLoader,
    private val source: TileSource,
) {
    val tiles = mutableStateMapOf<TileKey, ImageBitmap>()
    private val inFlight = HashSet<TileKey>()

    fun get(key: TileKey): ImageBitmap? = tiles[key]

    /** Requests [key] if not already loaded or loading. */
    fun request(scope: CoroutineScope, key: TileKey) {
        if (key in tiles || key in inFlight) return
        inFlight += key
        scope.launch {
            val bitmap = runCatching {
                val request = ImageRequest.Builder(context)
                    .data(source.url(key.z, key.x, key.y))
                    .size(Size(source.tileSizePx, source.tileSizePx))
                    .allowHardware(false)
                    .build()
                val result = loader.execute(request)
                (result as? SuccessResult)?.drawable
                    ?.let { it as? BitmapDrawable }
                    ?.bitmap
                    ?.asImageBitmap()
            }.getOrNull()
            inFlight -= key
            if (bitmap != null) tiles[key] = bitmap
        }
    }
}
