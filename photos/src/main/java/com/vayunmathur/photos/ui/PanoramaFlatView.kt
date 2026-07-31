package com.vayunmathur.photos.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.photos.data.Photo

/**
 * Flat panorama viewer: renders a wide (cylindrical) panorama as a rectangle
 * filling the viewport height, showing only the horizontal slice that fits and
 * letting the user scroll sideways across the rest. Unlike [PanoramaSphereView]
 * this is a plain 2D image — no sphere projection, no 360 view.
 */
@Composable
fun PanoramaFlatView(photo: Photo, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    BoxWithConstraints(modifier) {
        val viewportHeight = maxHeight
        val aspect = if (photo.height > 0) photo.width.toFloat() / photo.height.toFloat() else 1f
        val imageWidth = viewportHeight * aspect

        // Start centered so the user sees the middle of the panorama on open.
        LaunchedEffect(scrollState.maxValue) {
            scrollState.scrollTo(scrollState.maxValue / 2)
        }

        Row(Modifier.horizontalScroll(scrollState)) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(photo.uri.toUri())
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .height(viewportHeight)
                    .width(imageWidth),
                contentScale = ContentScale.FillHeight
            )
        }
    }
}
