package com.vayunmathur.library.image.compose

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Plays an animated image (GIF, animated WebP/AVIF) from [uri], looping forever.
 *
 * Decodes with [ImageDecoder.decodeDrawable] instead of going through
 * [com.vayunmathur.library.image.ImageLoader], whose pipeline is Bitmap-only and
 * so can never yield more than the first frame.
 */
@Composable
fun AnimatedImage(uri: Uri, contentDescription: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val drawable by produceState<Drawable?>(initialValue = null, key1 = uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeDrawable(
                    ImageDecoder.createSource(context.contentResolver, uri)
                )
            }.getOrNull()
        }
    }

    val current = drawable ?: return

    AndroidView(
        factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        modifier = modifier,
        update = { view ->
            view.contentDescription = contentDescription
            if (view.drawable !== current) view.setImageDrawable(current)
            (current as? AnimatedImageDrawable)?.apply {
                repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                if (!isRunning) start()
            }
        },
        onRelease = { (it.drawable as? AnimatedImageDrawable)?.stop() },
    )
}
