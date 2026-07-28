package com.vayunmathur.games.voxels.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.darkColorScheme

private val VoxelsDark = darkColorScheme(
    primary = Color(0xFF7CB342),
    secondary = Color(0xFF5D4037),
    tertiary = Color(0xFF81D4FA),
    background = Color(0xFF0E1A0F),
    surface = Color(0xFF1E2D1F),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.Black,
    onBackground = Color(0xFFE8F5E9),
    onSurface = Color(0xFFE8F5E9),
    primaryContainer = Color(0xFF33691E),
    secondaryContainer = Color(0xFF3E2723),
    error = Color(0xFFCF6679)
)

@Composable
fun VoxelsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = VoxelsDark, content = content)
}
