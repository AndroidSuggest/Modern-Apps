package com.vayunmathur.library.map

import androidx.compose.ui.graphics.ImageBitmap

/**
 * A georeferenced translucent image drawn over the basemap. The [bitmap] is stretched into
 * the screen quad of [bounds]' corners: axis-aligned north-up, rotated with the basemap
 * under a bearing (see [RasterMap]). Replaces maplibre's
 * `RasterLayer` + `rememberImageSource` + `PositionQuad`.
 */
data class ImageOverlay(
    val bitmap: ImageBitmap,
    val bounds: GeoBounds,
    val opacity: Float = 1f,
)
