package com.vayunmathur.photos.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize

/**
 * Tap (toggle chrome) + double-tap (zoom toggle) + pinch/pan for the photo viewer.
 * Reads [currentZoom] as a [State] so pinches update without recomposing; writes
 * go through [onZoomUpdate]. [containerSize] is the measured image container used
 * to clamp the pan offset.
 */
@Composable
internal fun photoZoomGestures(
    currentZoom: State<ZoomState>,
    /** Live reader for the measured container, used to clamp the pan offset. */
    containerSize: () -> IntSize,
    onZoomUpdate: (ZoomState) -> Unit,
    onToggleMetadata: () -> Unit,
): Modifier {
    return Modifier
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { onToggleMetadata() },
                onDoubleTap = {
                    val newScale =
                        if (currentZoom.value.scale > 1f) 1f else 2.5f
                    onZoomUpdate(
                        ZoomState(
                            scale = newScale,
                            offset = Offset.Zero
                        )
                    )
                }
            )
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
                    val zoomChange = event.calculateZoom()
                    val panChange = event.calculatePan()

                    val isPinching = zoomChange != 1f
                    val zoom = currentZoom.value
                    val isZoomed = zoom.scale > 1.01f

                    if (isZoomed || isPinching) {
                        val newScale =
                            (zoom.scale * zoomChange).coerceIn(
                                1f,
                                5f
                            )

                        if (newScale > 1f) {
                            val size = containerSize()
                            val maxX = (size.width * (newScale - 1) / 2)
                            val maxY = (size.height * (newScale - 1) / 2)

                            val newOffset = zoom.offset + panChange

                            val isAtLeftEdge =
                                newOffset.x >= maxX && panChange.x > 0
                            val isAtRightEdge =
                                newOffset.x <= -maxX && panChange.x < 0

                            val boundedOffset =
                                Offset(
                                    newOffset.x.coerceIn(-maxX, maxX),
                                    newOffset.y.coerceIn(-maxY, maxY)
                                )

                            onZoomUpdate(
                                ZoomState(
                                    scale = newScale,
                                    offset = boundedOffset
                                )
                            )

                            if (isPinching || (!isAtLeftEdge && !isAtRightEdge)
                            ) {
                                event.changes.forEach { it.consume() }
                            }
                        } else {
                            onZoomUpdate(
                                ZoomState(scale = 1f, offset = Offset.Zero)
                            )
                        }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
}
