package com.vayunmathur.health.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Color palette for the sleep hypnogram, sourced from MaterialTheme. */
data class HypnogramColors(
    val awake: Color,
    val rem: Color,
    val core: Color,
    val deep: Color,
)

/**
 * Fixed sleep-stage colors modelled on Google Fit / Health's hypnogram.
 *
 * These are intentionally *not* theme-driven — the previous MaterialTheme
 * palette mapped every stage to a tonal variant of the same hue, which made
 * them blend together (everything looked like a shade of white in dark mode
 * and a shade of the seed color in light mode). Distinct hues across the
 * cool→warm range make each stage instantly recognizable in both modes.
 */
val SleepStageColorAwake: Color = Color(0xFFF4B400) // Google amber — warm, stands out from sleep
val SleepStageColorRem: Color = Color(0xFF4FC3F7)   // Light cyan — REM, dream sleep
val SleepStageColorCore: Color = Color(0xFF4285F4)  // Google blue — light/core sleep
val SleepStageColorDeep: Color = Color(0xFF1A237E)  // Deep indigo — deep sleep

private val HypnogramColorsDefault = HypnogramColors(
    awake = SleepStageColorAwake,
    rem = SleepStageColorRem,
    core = SleepStageColorCore,
    deep = SleepStageColorDeep,
)

/**
 * Returns the shared [HypnogramColors] palette. Kept as a function (not a
 * direct constant import) so existing call sites — `val colors = hypnogramColors()`
 * — continue to compile unchanged.
 */
@Composable
fun hypnogramColors(): HypnogramColors = HypnogramColorsDefault
