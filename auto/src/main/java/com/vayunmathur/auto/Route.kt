package com.vayunmathur.auto

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/**
 * Auto has two phone-side screens. The interesting interface lives on the car's display, not
 * here; these are only somewhere to see whether a car is attached and how it is attached.
 */
@Serializable
sealed interface Route : NavKey {
    /** Connection state and how to attach a car. */
    @Serializable
    data object Home : Route

    /** USB + wireless bring-up, the winning transport, and the MAOS role. */
    @Serializable
    data object Pairing : Route

    /** The car dock pin picker: which car apps sit in the bottom bar. */
    @Serializable
    data object PinnedApps : Route
}
