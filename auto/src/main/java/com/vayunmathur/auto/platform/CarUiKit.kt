package com.vayunmathur.auto.platform

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.util.StateSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.GridLayout

/**
 * Shared drawing helpers for the car surface (`CarDisplay` family).
 *
 * One home for the framework drawables every card/rail/drawer view uses, so
 * the split component files share them instead of duplicating: the focus
 * ring, the circular launcher mask, dp math, and the shared metrics. All
 * `internal` (same-module car UI only), all Views (the private virtual
 * display has no Compose lifecycle owner).
 */
internal fun Context.carDp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

/**
 * Focus-ring background shared by rail buttons, cells and media
 * actions: transparent idle, highlighted on focus/press (the `kwi`
 * ring + ripple shape, approximated with framework drawables since
 * gearhead's own drawable cannot be copied).
 */
internal fun Context.carFocusRingBackground(): android.graphics.drawable.StateListDrawable {
    val idle = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
    val focused = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = carDp(8).toFloat()
        setColor(Color.parseColor("#33FFFFFF"))
        setStroke(carDp(2), Color.parseColor("#8AB4F8"))
    }
    return android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(android.R.attr.state_pressed), focused)
        addState(StateSet.WILD_CARD, idle)
    }
}

internal fun circularOutline(): ViewOutlineProvider = object : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        outline.setOval(0, 0, view.width, view.height)
    }
}

/** Shared car-surface metrics + palette (single source for all components). */
internal object CarUiMetrics {
    const val MATCH = android.view.ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
    const val COLUMNS = 3
    const val UNDEFINED_COLUMN = GridLayout.UNDEFINED
    val FILL: GridLayout.Alignment = GridLayout.FILL

    /** Hotseat shows this many apps before the drawer cell; the rest live in the drawer. */
    const val MAX_DOCK_APPS = 4

    /** Package + label fallback identifying the Maps slot for the nav card. */
    const val MAPS_PACKAGE = "com.vayunmathur.maps"
    const val MAPS_LABEL = "map"

    /**
     * Ceiling for the continuous frame invalidator: the encoder paces
     * output to its own configured rate, so invalidating faster only
     * burns CPU compositing frames the encoder drops.
     */
    const val MAX_INVALIDATE_FPS = 60

    /** Held-call row alpha (`button_disabled` parity). */
    const val BUTTON_DISABLED_ALPHA = 178 / 255f

    const val COLOR_ROOT_DAY = "#101418"
    const val COLOR_RAIL_DAY = "#161C24"
    const val COLOR_CARD_DAY = "#1B2430"
    const val COLOR_ACCENT = "#8AB4F8"
    const val COLOR_SUBTITLE = "#C7CFD9"
    const val COLOR_TRACK = "#2A3138"
    const val COLOR_CARD_NIGHT = "#FF191F27"
    const val COLOR_DRAWER_NIGHT = "#FF172026"
    const val COLOR_RAIL_NIGHT = "#FF101418"
    const val COLOR_LOCKOUT_DAY = "#E6FAFAFA"
    const val COLOR_LOCKOUT_NIGHT = "#E6172026"
    const val COLOR_PARKED_HEADER = "#FF37474F"
    const val COLOR_SCRIM_ASSISTANT = "#151616"

    /** Progress fraction in [0,1]; half (indeterminate-ish) with no duration. */
    fun progressFraction(positionMs: Long, durationMs: Long?): Float {
        val duration = durationMs?.takeIf { it > 0 } ?: return 0.5f
        return (positionMs.coerceAtLeast(0).toFloat() / duration).coerceIn(0f, 1f)
    }
}
