package com.vayunmathur.games.unblockjam.ui
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.vayunmathur.games.unblockjam.data.LevelPack

private val CustomDarkColorScheme = darkColorScheme(
    primary = DarkBrown,
    secondary = Brown,
    tertiary = Color.Red,
    background = Brown,
    surface = DarkBrown,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    primaryContainer = Orange,
    secondaryContainer = WarmGray,
    error = Color.Red
)

@Composable
fun UnblockJamTheme(
    pack: LevelPack? = null,
    content: @Composable () -> Unit
) {
    val colorScheme = pack?.colorScheme?.let {
        darkColorScheme(
            primary = Color(it.primary),
            secondary = Color(it.secondary),
            tertiary = Color(it.tertiary),
            background = Color(it.background),
            surface = Color(it.surface),
            onPrimary = Color(it.onPrimary),
            onSecondary = Color(it.onSecondary),
            onTertiary = Color(it.onTertiary),
            onBackground = Color(it.onBackground),
            onSurface = Color(it.onSurface),
            primaryContainer = Color(it.primaryContainer),
            secondaryContainer = Color(it.secondaryContainer),
            error = Color(it.error)
        )
    } ?: CustomDarkColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
