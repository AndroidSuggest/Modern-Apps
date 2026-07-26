package com.vayunmathur.library.widgets

import android.content.Context
import android.os.Build
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.glance.GlanceTheme
import androidx.glance.material3.ColorProviders

@Composable
fun DynamicThemeGlance(
    context: Context,
    content: @Composable () -> Unit
) {
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ColorProviders(
            light = dynamicLightColorScheme(context),
            dark = dynamicDarkColorScheme(context)
        )
    } else {
        ColorProviders(
            light = lightColorScheme(),
            dark = darkColorScheme()
        )
    }

    GlanceTheme(
        colors = colors,
        content = content
    )
}
