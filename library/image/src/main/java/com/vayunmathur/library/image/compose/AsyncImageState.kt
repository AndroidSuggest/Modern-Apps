package com.vayunmathur.library.image.compose

import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.asImageBitmap

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
 * Back-compat alias – some files check `AsyncImagePainter.State.Success`.
 * We expose inner State so `coil.compose.AsyncImagePainter` import migration is easy:
 * previously `import coil.compose.AsyncImagePainter` + check `is AsyncImagePainter.State.Success`.
 * We provide both `com.vayunmathur.library.image.compose.AsyncImagePainter` and
 * the nested `State` alias.
 */
object AsyncImagePainter {
    typealias State = AsyncImageState
}
