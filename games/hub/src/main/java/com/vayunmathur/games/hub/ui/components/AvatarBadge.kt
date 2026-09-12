package com.vayunmathur.games.hub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme

/**
 * Circular avatar badge for profile headers. Same sizing and surface as
 * [LevelBadge] so the two are interchangeable in header rows; the level
 * itself stays visible in the surrounding text.
 */
@Composable
fun AvatarBadge(symbol: String?, modifier: Modifier = Modifier, large: Boolean = false) {
    val size = if (large) 56.dp else 36.dp
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        AvatarIcon(symbol = symbol, tint = MaterialTheme.colorScheme.onPrimary)
    }
}
