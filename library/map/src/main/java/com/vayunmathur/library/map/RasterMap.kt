package com.vayunmathur.library.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import org.maplibre.spatialk.geojson.Position
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/** Placement of one tile in the viewport, in logical (dp) coordinates. */
private data class TileBox(
    val key: TileKey,
    val leftDp: Float,
    val topDp: Float,
    val sizeDp: Float,
)

/** Background shown behind tiles before/if they load. */
private val MapBackground = Color(0xFFE9E7E2)

/**
 * A Compose-native Web-Mercator raster tile viewer. Renders XYZ tiles from
 * [tileSource] on a Canvas with parent-tile fallback (no blank flashes while
 * panning/zooming), exposes a maplibre-compatible camera/projection via
 * [cameraState], and draws an optional georeferenced [imageOverlay] plus a
 * composable [content] overlay above the tiles.
 */
@Composable
fun RasterMap(
    cameraState: CameraState,
    modifier: Modifier = Modifier,
    tileSource: TileSource = TileSource.CartoVoyager,
    zoomRange: ClosedFloatingPointRange<Float> = 0f..20f,
    options: MapOptions = MapOptions(),
    imageOverlay: ImageOverlay? = null,
    onMapClick: (Position) -> Unit = {},
    onFrame: () -> Unit = {},
    content: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()

    val loader = remember(context) { ImageLoader(context) }
    val cache = remember(tileSource, loader) { TileCache(context, loader, tileSource) }

    val minZoom = tileSource.minZoom
    val maxZoom = tileSource.maxZoom
    val gestures = options.gestureOptions

    // Fire onFrame whenever the camera moves (drives per-frame reprojection).
    LaunchedEffect(cameraState.position, cameraState.viewportDp) {
        if (cameraState.viewportDp != null) onFrame()
    }

    // Compute the currently visible tiles (recomputed on move) and prefetch them.
    val position = cameraState.position
    val viewport = cameraState.viewportDp
    val visible: List<TileBox> = remember(position, viewport, tileSource) {
        if (viewport == null) emptyList() else computeVisibleTiles(position, viewport, minZoom, maxZoom)
    }
    LaunchedEffect(visible) { visible.forEach { cache.request(scope, it.key) } }

    Box(
        modifier
            .fillMaxSize()
            .background(MapBackground)
            .onSizeChanged {
                cameraState.setViewport(Size(it.width / density, it.height / density))
            }
            .pointerInput(cameraState, gestures, zoomRange) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (!gestures.isScrollEnabled && !gestures.isZoomEnabled) return@detectTransformGestures
                    cameraState.onGesture(
                        centroidDp = Offset(centroid.x / density, centroid.y / density),
                        panDp = Offset(pan.x / density, pan.y / density),
                        zoomChange = zoom,
                        minZoom = zoomRange.start.toDouble(),
                        maxZoom = zoomRange.endInclusive.toDouble(),
                        scrollEnabled = gestures.isScrollEnabled,
                        zoomEnabled = gestures.isZoomEnabled,
                    )
                }
            }
            .pointerInput(cameraState) {
                detectTapGestures { offset ->
                    val proj = cameraState.projection ?: return@detectTapGestures
                    onMapClick(proj.positionFromScreenLocation(DpOffset((offset.x / density).dp, (offset.y / density).dp)))
                }
            },
    ) {
        // Read cache.tiles inside the draw lambda so tile arrival recomposes.
        val tiles = cache.tiles
        Canvas(Modifier.fillMaxSize()) {
            for (box in visible) {
                drawTile(box, tiles, density, minZoom)
            }
            imageOverlay?.let { drawOverlay(it, cameraState) }
        }

        content()

        if (options.ornamentOptions.isAttributionEnabled) {
            Attribution(tileSource.attribution, Modifier.align(Alignment.BottomStart))
        }
    }
}

/** Draws one tile, falling back to a scaled ancestor when the exact tile is missing. */
private fun DrawScope.drawTile(
    box: TileBox,
    tiles: Map<TileKey, ImageBitmap>,
    density: Float,
    minZoom: Int,
) {
    val left = (box.leftDp * density).roundToInt()
    val top = (box.topDp * density).roundToInt()
    val right = ((box.leftDp + box.sizeDp) * density).roundToInt()
    val bottom = ((box.topDp + box.sizeDp) * density).roundToInt()
    val dstOffset = IntOffset(left, top)
    val dstSize = IntSize(right - left, bottom - top)

    val exact = tiles[box.key]
    if (exact != null) {
        drawImage(
            image = exact,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(exact.width, exact.height),
            dstOffset = dstOffset,
            dstSize = dstSize,
        )
        return
    }
    // Walk up the pyramid for a cached ancestor and draw its matching sub-region.
    var d = 1
    while (box.key.z - d >= minZoom) {
        val f = 1 shl d
        val pkey = TileKey(box.key.z - d, box.key.x / f, box.key.y / f)
        val ancestor = tiles[pkey]
        if (ancestor != null) {
            val subW = ancestor.width / f
            val subH = ancestor.height / f
            drawImage(
                image = ancestor,
                srcOffset = IntOffset((box.key.x % f) * subW, (box.key.y % f) * subH),
                srcSize = IntSize(subW, subH),
                dstOffset = dstOffset,
                dstSize = dstSize,
            )
            return
        }
        d++
    }
}

/** Stretches the overlay image into its bbox's screen rect (north-up, axis-aligned). */
private fun DrawScope.drawOverlay(overlay: ImageOverlay, cameraState: CameraState) {
    val proj = cameraState.projection ?: return
    val bbox = overlay.bounds
    val nw = proj.screenLocationFromPosition(Position(bbox.west, bbox.north))
    val se = proj.screenLocationFromPosition(Position(bbox.east, bbox.south))
    val left = nw.x.toPx().roundToInt()
    val top = nw.y.toPx().roundToInt()
    val right = se.x.toPx().roundToInt()
    val bottom = se.y.toPx().roundToInt()
    drawImage(
        image = overlay.bitmap,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(overlay.bitmap.width, overlay.bitmap.height),
        dstOffset = IntOffset(left, top),
        dstSize = IntSize((right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1)),
        alpha = overlay.opacity,
    )
}

@Composable
private fun Attribution(text: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.text.BasicText(
        text = text,
        modifier = modifier
            .padding(2.dp)
            .background(Color(0xB3FFFFFF))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        style = TextStyle(color = Color(0xFF444444), fontSize = 9.sp, fontFamily = FontFamily.SansSerif),
    )
}

/** Visible tiles for [position] within [viewport] (dp), at `floor(zoom)` clamped to source range. */
private fun computeVisibleTiles(
    position: CameraPosition,
    viewport: Size,
    minZoom: Int,
    maxZoom: Int,
): List<TileBox> {
    val zoom = position.zoom
    val z = floor(zoom).toInt().coerceIn(minZoom, maxZoom)
    val n = 1 shl z
    val tileSpanDp = Mercator.TILE_SIZE * 2.0.pow(zoom - z)
    val center = Mercator.project(position.target.longitude, position.target.latitude, zoom)
    val originX = center.x - viewport.width / 2.0
    val originY = center.y - viewport.height / 2.0

    val minTx = floor(originX / tileSpanDp).toInt()
    val maxTx = floor((originX + viewport.width) / tileSpanDp).toInt()
    val minTy = floor(originY / tileSpanDp).toInt()
    val maxTy = floor((originY + viewport.height) / tileSpanDp).toInt()

    val result = ArrayList<TileBox>()
    for (ty in minTy..maxTy) {
        if (ty < 0 || ty >= n) continue
        for (tx in minTx..maxTx) {
            val wrappedX = ((tx % n) + n) % n
            result += TileBox(
                key = TileKey(z, wrappedX, ty),
                leftDp = (tx * tileSpanDp - originX).toFloat(),
                topDp = (ty * tileSpanDp - originY).toFloat(),
                sizeDp = tileSpanDp.toFloat(),
            )
        }
    }
    return result
}
