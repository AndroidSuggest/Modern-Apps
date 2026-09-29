package com.vayunmathur.keyboard.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import com.vayunmathur.library.ui.rememberHaptics

/**
 * Whether key ticks are enabled, from the keyboard's haptic setting (default on, toggled in
 * SetupScreen). Provided once in [KeyboardScreen]; every key and tappable strip reads it here
 * so the toggle needs no plumbing through the dozen components below.
 *
 * The shared [rememberHaptics] already respects the *system* haptic setting, so a tick fires
 * only when both the app toggle and the system setting allow it.
 */
val LocalKeyHapticsEnabled = compositionLocalOf { true }

/**
 * A stable key-tick lambda for gesture and click callbacks. Read once during composition —
 * haptics can't be read from inside a `pointerInput` block or a stored callback, so call
 * sites capture this and invoke it where the press commits.
 */
@Composable
fun rememberKeyHapticTick(): () -> Unit {
    val haptics = rememberHaptics()
    val enabled = LocalKeyHapticsEnabled.current
    return remember(haptics, enabled) {
        { if (enabled) haptics.keyPress() }
    }
}
