package com.vayunmathur.library.widgets

import android.content.Context
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.glance.GlanceTheme
import androidx.glance.material3.ColorProviders

@Composable
fun DynamicThemeGlance(
    context: Context,
    content: @Composable () -> Unit
) {
    val colors = ColorProviders(
        light = dynamicLightColorScheme(context),
        dark = dynamicDarkColorScheme(context)
    )

    GlanceTheme(
        colors = colors,
        content = content
    )
}
