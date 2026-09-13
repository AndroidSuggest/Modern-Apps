package com.vayunmathur.pdf.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.TransformableState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import com.vayunmathur.library.ocr.OcrEngine
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.pdf.R
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Zoomable page list shared by the compact body and the Expanded viewer slot.
 * Captures the screen's zoom/pan/search/edit state directly; logic verbatim.
 */
@Composable
internal fun SafePdfPageViewer(
    modifier: Modifier = Modifier,
    bundle: SafePdfDocumentBundle,
    state: SafePdfViewerState,
    zoom: SafePdfZoomState,
    transform: TransformableState,
    listState: LazyListState,
    launchers: SafePdfLauncherSet,
    showPageIndicator: Boolean,
) {
    val scope = rememberCoroutineScope()
    val document = bundle.document
    val ocr: OcrEngine = bundle.ocrEngine

    // Double-tap to zoom in/out with animation centered on the tap.
    val latestZoom by rememberUpdatedState(zoom.zoom)
    val latestPan by rememberUpdatedState(zoom.pan)
    val latestViewport by rememberUpdatedState(zoom.viewportSize)
    val latestEditMode by rememberUpdatedState(state.editMode)
    val latestTool by rememberUpdatedState(state.tool)

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { zoom.viewportSize = it }
            // Track finger count in the Initial pass (before the LazyColumn's
            // scroll reacts in the Main pass) so multi-touch disables scroll.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        zoom.multiTouch = e.changes.count { it.pressed } >= 2
                    }
                }
            }
            // Double-tap to zoom in/out, centered on the tap with animation.
            // Disabled in edit mode (except SELECT) to avoid conflicting with annotation taps.
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        if (latestEditMode && latestTool != EditTool.SELECT) return@detectTapGestures
                        val vp = latestViewport
                        if (vp == IntSize.Zero) return@detectTapGestures
                        val targetZoom = if (latestZoom < DOUBLE_TAP_ZOOM_THRESHOLD) DOUBLE_TAP_ZOOM else 1f
                        val origin = Offset(vp.width / 2f, 0f)
                        // Where the tap point should end up: zoomed-in it's offset from center.
                        val targetPan = if (targetZoom <= 1f) {
                            Offset.Zero
                        } else {
                            val zoomDelta = targetZoom / latestZoom.coerceAtLeast(0.001f)
                            val pivoted = latestPan + (tapOffset - origin - latestPan) * (1f - zoomDelta)
                            clampPan(pivoted, targetZoom, vp)
                        }
                        scope.launch {
                            val startZoom = latestZoom
                            val startPan = latestPan
                            val anim = Animatable(0f)
                            anim.animateTo(1f, animationSpec = tween(durationMillis = 250)) {
                                val t = value
                                zoom.setZoomPan(
                                    startZoom + (targetZoom - startZoom) * t,
                                    Offset(
                                        startPan.x + (targetPan.x - startPan.x) * t,
                                        startPan.y + (targetPan.y - startPan.y) * t,
                                    ),
                                )
                            }
                            zoom.setZoomPan(targetZoom, targetPan)
                        }
                    }
                )
            }
            // Above the graphicsLayer so gesture coordinates are plain viewport pixels.
            .transformable(transform, enabled = !state.editMode || state.tool == EditTool.SELECT)
            // Zooming below 1 draws the page narrower than the screen. Lay the content
            // out taller by 1/zoom so that, scaled back down, it still fills the
            // viewport height — otherwise it would shrink to a band with blank space
            // above and below. The layout width stays the viewport width, so pages keep
            // rasterizing at full resolution and are only downscaled when drawn.
            .layout { measurable, constraints ->
                val h = if (zoom.zoom < 1f && constraints.hasBoundedHeight) {
                    (constraints.maxHeight / zoom.zoom).roundToInt()
                } else {
                    constraints.maxHeight
                }
                val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
            }
            .graphicsLayer {
                scaleX = zoom.zoom
                scaleY = zoom.zoom
                translationX = zoom.pan.x
                translationY = zoom.pan.y
                // Pivot on the top edge: keeps the first visible page anchored to the top
                // of the screen across zoom changes, and makes the taller-than-viewport
                // layout above scale back to exactly the viewport height.
                transformOrigin = TransformOrigin(0.5f, 0f)
            },
    ) {
        when (val loadState = bundle.loadState) {
            LoadState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is LoadState.Error -> Text(
                text = androidx.compose.ui.res.stringResource(
                    if (loadState.notPdf) R.string.safe_pdf_not_a_pdf else R.string.safe_pdf_error
                ),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            is LoadState.Loaded -> LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                userScrollEnabled = !zoom.multiTouch,
            ) {
                items((0 until state.pageCount).toList()) { index ->
                    val pageHighlights = state.matches.filter { it.first == index }.map { it.second }
                    val current = state.matches.getOrNull(state.matchIndex)
                    val currentHighlight = if (current?.first == index) current.second else null
                    SafePdfPageItem(
                        document = loadState.document,
                        index = index,
                        version = (state.pageVersions[index] ?: 0) + state.pageMgrVersion,
                        ocr = ocr,
                        editMode = state.editMode,
                        tool = state.tool,
                        shape = state.shape,
                        markup = state.markup,
                        color = state.drawColor,
                        strokeWidth = state.strokeWidth,
                        selected = state.selected?.takeIf { it.first == index }?.second,
                        highlights = pageHighlights,
                        currentHighlight = currentHighlight,
                        scope = state.scope,
                        onSelect = { annotId -> state.selected = annotId?.let { index to it } },
                        onEdited = { state.markEdited(index) },
                        onCreated = { id -> state.registerCreated(index, id) },
                        onMoved = { id, oldR, newR -> state.registerMoved(index, id, oldR, newR) },
                        onFormEdited = { state.markEdited(index); state.nonUndoDirty = true },
                        onPageWidth = { w -> zoom.onPageWidth(w) },
                        onLinkPage = { p -> scope.launch { listState.animateScrollToItem(p.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))) } },
                        textSession = state.textSession?.takeIf { it.page == index },
                        onStartText = { s -> state.startText(document, s) },
                        onTextChange = { v -> state.textSession = state.textSession?.copy(value = v) },
                        onCommitText = { state.commitTextAndClear(document) },
                        onRequestImage = { pt -> state.imageTarget = index to pt; launchers.pickImage() },
                        onRequestNote = { pt -> state.pendingNote = index to pt },
                        onRequestCallout = { a, b -> state.pendingCallout = Triple(index, a, b) },
                        polyDraft = state.polyDraft?.takeIf { it.page == index },
                        onAddPolyPoint = { pt -> state.addPolyPoint(index, pt) },
                    )
                }
            }
        }
        // Page-number indicator on the right edge, shown while scrolling.
        if (state.pageCount > 1 && showPageIndicator) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
                    .background(Color(0xCC000000), CircleShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "${listState.firstVisibleItemIndex + 1} / ${state.pageCount}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
