package com.vayunmathur.library.image.compose

import androidx.compose.ui.graphics.painter.BitmapPainter

/**
 * Mirrors Coil's AsyncImagePainter.State hierarchy plus SubcroppedImage usage
 * `state as Success -> painter.intrinsicSize`.
 */
sealed class AsyncImageState {
    data object Loading : AsyncImageState()
    data object Empty : AsyncImageState()
    data class Success(
        val painter: BitmapPainter,
    ) : AsyncImageState()
    data class Error(
        val throwable: Throwable? = null,
    ) : AsyncImageState()
}

/**
 * Compat shims so legacy checks `is AsyncImagePainter.State.Success` still compile.
 * Coil had `class AsyncImagePainter { sealed class State { ... } }`.
 * We expose both `AsyncImagePainter` object with nested typealias and top-level compat.
 * For best compatibility we also provide a real class `State` via typealias chain – but
 * Kotlin disallows `Typealias.State` resolution in some versions, so we also fix call sites
 * to check `is AsyncImageState.Success`.
 *
 * To keep source compat for any remaining call site that still does
 * `AsyncImagePainter.State.Success`, we expose a wrapper object whose `State` contains `Success` ctor.
 */
object AsyncImagePainter {
    typealias State = AsyncImageState
}

// Top-level alias for convenience if file did `import coil.compose.AsyncImagePainter` and then `State`
typealias AsyncImagePainterState = AsyncImageState
