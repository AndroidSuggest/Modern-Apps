package com.vayunmathur.launcher.ui

import androidx.compose.ui.geometry.Rect
import com.vayunmathur.launcher.platform.HomeActions
import com.vayunmathur.launcher.platform.WorkspaceItem

internal fun HomeActions.launchFrom(item: WorkspaceItem, bounds: Rect) = launch(
    item,
    bounds.left.toInt(),
    bounds.top.toInt(),
    bounds.right.toInt(),
    bounds.bottom.toInt(),
)
