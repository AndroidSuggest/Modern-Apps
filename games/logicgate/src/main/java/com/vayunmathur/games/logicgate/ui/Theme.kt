package com.vayunmathur.games.logicgate.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.darkColorScheme

// Turing Complete palette: top header dark plum #2B2A3E, canvas slate-blue #2C4E66, side panels #1C2635, bottom testbench #2A283E
private val LogicDark = darkColorScheme(
    primary = Color(0xFF3DD8C2),
    secondary = Color(0xFF2D4A66),
    tertiary = Color(0xFFFF8A65),
    background = Color(0xFF0E1724), // overall outside
    surface = Color(0xFF1B2636),    // panels
    onPrimary = Color.Black,
    onSecondary = Color.White,
    onTertiary = Color.Black,
    onBackground = Color(0xFFC8D6E5),
    onSurface = Color(0xFFC8D6E5),
    primaryContainer = Color(0xFF0E3A36),
    secondaryContainer = Color(0xFF23344A),
    error = Color(0xFFFF8A80)
)

@Composable
fun LogicGateTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LogicDark, content = content)
}
