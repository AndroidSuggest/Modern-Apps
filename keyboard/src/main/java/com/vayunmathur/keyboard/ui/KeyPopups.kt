@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.vayunmathur.keyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/** Where a key ended up on screen, as of the last layout pass. */
internal class KeyBounds {
    var left = 0f
    var width = 0f
}

/**
 * Where the alternates row's left edge sits relative to the key's: centred on the key, then
 * nudged back inside the screen. The gesture that picks from the row uses the same number, so
 * what the finger is over is always what is highlighted.
 */
internal fun alternatesOffset(bounds: KeyBounds, itemWidth: Float, count: Int, screenWidth: Float): Float {
    val total = itemWidth * count
    val centred = bounds.left + (bounds.width - total) / 2f
    val clamped = centred.coerceIn(0f, (screenWidth - total).coerceAtLeast(0f))
    return clamped - bounds.left
}

/** True while the finger is still over the key it went down on. */
internal fun Offset.isInside(size: IntSize): Boolean =
    x >= 0f && y >= 0f && x <= size.width && y <= size.height

internal fun indexAt(x: Float, popupOffset: Float, itemWidth: Float, count: Int): Int =
    (((x - popupOffset) / itemWidth).toInt()).coerceIn(0, count - 1)

/** The row of options above a held key, with the one under the finger highlighted. */
@Composable
internal fun AlternatesPopup(options: String, selected: Int, itemWidth: Dp, offsetX: Int) {
    Popup(
        popupPositionProvider = remember(offsetX) { AlternatesPositionProvider(offsetX) },
        properties = PopupProperties(focusable = false, clippingEnabled = false),
    ) {
        Row(
            modifier = Modifier
                .padding(bottom = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceBright),
        ) {
            options.forEachIndexed { index, c ->
                Box(
                    modifier = Modifier
                        .width(itemWidth)
                        .height(52.dp)
                        .background(
                            if (index == selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = c.toString(),
                        color = if (index == selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        fontSize = 22.sp,
                    )
                }
            }
        }
    }
}

internal val AlternateWidth = 44.dp

/** Places the alternates row directly above the key, shifted by the clamped [offsetX]. */
internal class AlternatesPositionProvider(private val offsetX: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(anchorBounds.left + offsetX, anchorBounds.top - popupContentSize.height)
}

/** The pop-up character preview shown above a held key. */
@Composable
internal fun KeyPreview(label: String) {
    Popup(
        popupPositionProvider = KeyPreviewPositionProvider,
        properties = PopupProperties(focusable = false, clippingEnabled = false),
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 2.dp, vertical = 4.dp)
                .defaultMinSize(minWidth = 46.dp, minHeight = 50.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 30.sp,
            )
        }
    }
}

/** Positions the preview centred horizontally over the key and directly above it. */
internal object KeyPreviewPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val y = anchorBounds.top - popupContentSize.height
        return IntOffset(x, y)
    }
}
