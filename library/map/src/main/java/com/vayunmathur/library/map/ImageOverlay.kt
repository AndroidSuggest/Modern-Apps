package com.vayunmathur.library.map

import androidx.compose.ui.graphics.ImageBitmap
import org.maplibre.spatialk.geojson.BoundingBox

/**
 * A georeferenced translucent image drawn over the basemap. Because the map is
 * north-up and axis-aligned, the [bitmap] is stretched into the screen rect of
 * [bounds]'s corners (see [RasterMap]). Replaces maplibre's
 * `RasterLayer` + `rememberImageSource` + `PositionQuad`.
 */
data class ImageOverlay(
    val bitmap: ImageBitmap,
    val bounds: BoundingBox,
    val opacity: Float = 1f,
)
