package com.vayunmathur.auto.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.vayunmathur.library.ui.IconApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Nominal car-launcher icon edge. Every tile and dock cell sizes around this. */
val CarLauncherIconSize = 48.dp

/**
 * One car app's icon.
 *
 * Drawn with `Image(bitmap = ...)` rather than through the shared `Icon…()`
 * helpers, because an icon from `PackageManager` is an arbitrary `Drawable` —
 * adaptive, sometimes badged — and neither `ImageVector` nor `Painter` can
 * represent one. Rasterisation happens off the main thread the first time.
 * Until it resolves, and for an app whose icon fails to load, a placeholder
 * draws so the grid never reflows.
 *
 * This is not a hole in the "all icons come from `Icons.kt`" rule: that rule
 * governs semantic UI glyphs — home, signal, battery — which still all come
 * from there. These are third-party app artwork.
 */
@Composable
fun CarAppIcon(icon: Drawable?, label: String, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val sizePx = with(density) { CarLauncherIconSize.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, icon) {
        val source = icon ?: return@produceState
        value = withContext(Dispatchers.IO.limitedParallelism(4)) {
            runCatching { source.toBitmap(sizePx, sizePx).asImageBitmap() }.getOrNull()
        }
    }
    val imageModifier = modifier.size(CarLauncherIconSize)
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = label,
            contentScale = ContentScale.Fit,
            modifier = imageModifier,
        )
    } else {
        IconApps(imageModifier)
    }
}
