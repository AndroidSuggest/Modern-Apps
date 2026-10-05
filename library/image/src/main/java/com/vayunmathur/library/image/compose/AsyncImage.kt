package com.vayunmathur.library.image.compose

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.image.ImageLoader
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.ImageResult

/**
 * Coil-like AsyncImage backed by our ImageLoader (HttpURLConnection + custom caches + SVG + Video).
 *
 * Supported model types (Any?): Uri, String URL, java.io.File, ByteArray, Bitmap, ImageRequest.
 * Mirrors signature used across codebase:
 * `AsyncImage(model, contentDescription, modifier, contentScale, colorFilter, onState)`
 */
@Composable
fun AsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    colorFilter: ColorFilter? = null,
    alpha: Float = 1f,
    imageLoader: ImageLoader? = null,
    onState: ((AsyncImageState) -> Unit)? = null,
    alignment: Alignment = Alignment.Center,
) {
    val context = LocalContext.current
    val loader = imageLoader ?: remember(context) { ImageLoader.get(context) }

    val request = rememberImageRequest(model, context)

    // Synchronous memory-cache probe. On a hit the bitmap is available before the
    // first frame, so a scrolled-in item paints immediately instead of showing an
    // empty box for a frame and re-running its crossfade over an image that was
    // already in memory.
    val seeded = rememberSeededBitmap(request, loader)

    val bitmapState = rememberAsyncBitmap(request, seeded, loader, onState)

    val bitmap = bitmapState.value

    // Crossfade alpha if requested
    val shouldCrossfade = request?.crossfade == true
    val targetAlpha = if (bitmap != null) alpha else 0f
    val animatedAlpha by animateFloatAsState(
        targetValue = if (shouldCrossfade) targetAlpha else alpha,
        label = "asyncImageCrossfade"
    )

    Box(modifier = modifier, contentAlignment = alignment) {
        if (bitmap != null) {
            // Use Android-like scaling; honor colorFilter and contentScale
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.matchParentSize().let {
                    if (shouldCrossfade) it.alpha(animatedAlpha) else it
                },
                contentScale = contentScale,
                colorFilter = colorFilter,
                alignment = alignment,
            )
        }
    }
}

@Composable
private fun rememberImageRequest(model: Any?, context: Context): ImageRequest? =
    remember(model) {
        when (model) {
            null -> null
            is ImageRequest -> model
            else -> ImageRequest.Builder(context).data(model).build()
        }
    }

@Composable
private fun rememberAsyncBitmap(
    request: ImageRequest?,
    seeded: Bitmap?,
    loader: ImageLoader,
    onState: ((AsyncImageState) -> Unit)?,
) = produceState<Bitmap?>(initialValue = seeded, key1 = request) {
    if (request == null) {
        value = null
        onState?.invoke(AsyncImageState.Empty)
        return@produceState
    }
    if (seeded != null) {
        // Served by the probe. `value` is assigned explicitly because
        // produceState only applies initialValue on first composition — on a
        // key change it still holds the previous request's bitmap.
        value = seeded
        onState?.invoke(AsyncImageState.Success(BitmapPainter(seeded.asImageBitmap())))
        return@produceState
    }
    onState?.invoke(AsyncImageState.Loading)
    when (val outcome = loadImageBitmap(request, loader)) {
        is ImageLoadOutcome.Ready -> {
            value = outcome.bitmap
            val painter = BitmapPainter(outcome.bitmap.asImageBitmap())
            onState?.invoke(AsyncImageState.Success(painter))
        }
        is ImageLoadOutcome.Failed -> {
            value = null
            onState?.invoke(AsyncImageState.Error(outcome.throwable))
        }
    }
}

@Composable
private fun rememberSeededBitmap(request: ImageRequest?, loader: ImageLoader): Bitmap? =
    remember(request) { request?.let { loader.peekMemoryCache(it) } }

private sealed interface ImageLoadOutcome {
    data class Ready(val bitmap: Bitmap) : ImageLoadOutcome
    data class Failed(val throwable: Throwable) : ImageLoadOutcome
}

private suspend fun loadImageBitmap(request: ImageRequest, loader: ImageLoader): ImageLoadOutcome {
    val result = runCatching { loader.execute(request) }
        .getOrElse { return ImageLoadOutcome.Failed(it) }
    return when (result) {
        is ImageResult.Success -> ImageLoadOutcome.Ready(result.bitmap)
        is ImageResult.Error -> ImageLoadOutcome.Failed(result.throwable)
    }
}

/**
 * Overload matching `coil.compose.AsyncImage(model: ImageRequest, ...)` convenience.
 */
@Composable
fun AsyncImage(
    model: ImageRequest?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    colorFilter: ColorFilter? = null,
    imageLoader: ImageLoader? = null,
    onState: ((AsyncImageState) -> Unit)? = null,
) {
    AsyncImage(
        model = model as Any?,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        colorFilter = colorFilter,
        imageLoader = imageLoader,
        onState = onState,
    )
}
