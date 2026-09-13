package com.vayunmathur.photos.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import com.vayunmathur.photos.data.AdjustmentLayer
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.LayerAdjustment

/** Positions a popup directly above its anchor (a "drop-up"). */
internal class AbovePopupPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = anchorBounds.left.coerceIn(0, maxX)
        val y = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

internal inline fun <reified T : LayerAdjustment> EditDocument.activeAdjustment(): T? =
    (activeLayer as? AdjustmentLayer)?.adjustment as? T
