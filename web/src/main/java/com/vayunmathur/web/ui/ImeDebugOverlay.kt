package com.vayunmathur.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/** TEMP DEBUG. DO NOT COMMIT. */
internal object ImeDebugState {
    var barBottomY by mutableIntStateOf(-1)
    var contentPadBottomPx by mutableIntStateOf(-1)
}

/**
 * TEMP DEBUG readout. Renders inside CONTENT (always above keyboard) so it is
 * visible in every state. DO NOT COMMIT.
 */
@Composable
internal fun ImeDebugOverlay() {
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    Box(
        Modifier
            .background(Color.Red.copy(alpha = 0.85f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = "ime=$imeBottom nav=$navBottom bar=${ImeDebugState.barBottomY} pad=${ImeDebugState.contentPadBottomPx}",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}
