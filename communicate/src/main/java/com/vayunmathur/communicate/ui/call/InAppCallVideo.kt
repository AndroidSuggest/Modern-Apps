package com.vayunmathur.communicate.ui.call

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import com.vayunmathur.library.ui.Spacing
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Renders the call's video. Renderers share the decoder's EGL context and are detached on release, since a
 * renderer handed frames after release crashes the decoder thread.
 */
@Composable
internal fun CallVideo(showRemote: Boolean, showLocal: Boolean) {
    val controller = InAppCallRegistry.videoController ?: return
    val eglContext = remember(controller) { controller.eglContext() } ?: return

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (showRemote) {
            AndroidView(
                factory = { context ->
                    SurfaceViewRenderer(context).apply {
                        init(eglContext, null)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        setEnableHardwareScaler(true)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { renderer ->
                    controller.attachRemoteRenderer(null)
                    renderer.release()
                },
                update = { renderer -> controller.attachRemoteRenderer(renderer) },
            )
        }
        if (showLocal) {
            SelfView(
                eglContext = eglContext,
                bounds = DpSize(maxWidth, maxHeight),
                onRenderer = { renderer -> controller.attachLocalRenderer(renderer) },
                onRelease = { controller.attachLocalRenderer(null) },
            )
        }
    }
}

/**
 * The self-view: draggable anywhere on screen and pinch-resizable, like a picture-in-picture window.
 *
 * Position and scale are kept in this composable rather than in call state — they are a view preference, not
 * something the call or the other participant cares about. Both are clamped to [bounds] so the window cannot
 * be dragged or grown off-screen where it could not be recovered.
 */
@Composable
internal fun SelfView(
    eglContext: org.webrtc.EglBase.Context,
    bounds: DpSize,
    onRenderer: (SurfaceViewRenderer) -> Unit,
    onRelease: () -> Unit,
) {
    val density = LocalDensity.current
    var scale by remember { mutableFloatStateOf(1f) }
    val width = (SELF_VIEW_WIDTH.dp * scale).coerceIn(SELF_VIEW_MIN.dp, bounds.width * 0.6f)
    val height = width * SELF_VIEW_ASPECT

    // Starts bottom-end, clear of the controls.
    var offset by remember(bounds) {
        mutableStateOf(
            with(density) {
                Offset(
                    x = (bounds.width - width - Spacing.lg).toPx(),
                    y = (bounds.height - height - SELF_VIEW_BOTTOM_INSET.dp).toPx(),
                )
            },
        )
    }

    val maxOffset = with(density) {
        Offset((bounds.width - width).toPx().coerceAtLeast(0f), (bounds.height - height).toPx().coerceAtLeast(0f))
    }
    // Re-clamped after a resize, so growing near an edge does not push it out of reach.
    offset = Offset(offset.x.coerceIn(0f, maxOffset.x), offset.y.coerceIn(0f, maxOffset.y))

    Box(
        modifier = Modifier
            .offset { IntOffset(offset.x.toInt(), offset.y.toInt()) }
            .size(width = width, height = height)
            .clip(RoundedCornerShape(Spacing.md))
            .pointerInput(bounds, width) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(SELF_VIEW_MIN_SCALE, SELF_VIEW_MAX_SCALE)
                    offset = Offset(
                        (offset.x + pan.x).coerceIn(0f, maxOffset.x),
                        (offset.y + pan.y).coerceIn(0f, maxOffset.y),
                    )
                }
            },
    ) {
        AndroidView(
            factory = { context ->
                SurfaceViewRenderer(context).apply {
                    init(eglContext, null)
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                    setMirror(true)
                    // Both renderers are SurfaceViews, which the system composites as their own layers
                    // rather than in Compose's draw order. Without this the full-screen remote view covers
                    // the self-view the moment it appears — i.e. exactly when the call is answered.
                    setZOrderMediaOverlay(true)
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { renderer ->
                onRelease()
                renderer.release()
            },
            update = onRenderer,
        )
    }
}

internal const val SELF_VIEW_WIDTH = 108
internal const val SELF_VIEW_MIN = 72
internal const val SELF_VIEW_ASPECT = 4f / 3f
internal const val SELF_VIEW_MIN_SCALE = 0.7f
internal const val SELF_VIEW_MAX_SCALE = 2.5f

/** Initial gap above the control row, so the self-view does not start on top of the buttons. */
internal const val SELF_VIEW_BOTTOM_INSET = 220
