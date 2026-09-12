package com.vayunmathur.library.util

import androidx.compose.animation.ContentTransform
import androidx.navigation3.scene.Scene

/**
 * How a destination arrives and leaves.
 *
 * nav3's default is a slow crossfade for everything, and the app-wide slide that replaced it was no
 * better at saying *where* the user went: opening a photo, switching a tab and descending into
 * settings are different journeys that were all animated identically.
 *
 * Apps choose per destination with the `*Page()` helpers rather than by building transitions
 * themselves - nav3 and compose-animation are `implementation` dependencies of this module, so an
 * app module cannot name a [ContentTransform] even if it wanted to.
 */
enum class NavMotion {
    /** Descending a hierarchy - a list to its detail, a screen to its settings. */
    Detail,

    /** Content opening out of the thing that was tapped. */
    Zoom,

    /** Immersive content taking over the window - a viewer, a player, a game board. */
    Fullscreen,

    /** Moving between peers - bottom-bar destinations, tabs. */
    Sibling,

    /**
     * A component on the previous screen morphs into its counterpart on this one, via
     * [sharedContainer], [sharedCrop], [sharedText] or [sharedContent].
     *
     * The screen itself only crossfades. That is the whole point: if the destination also slid or
     * scaled, the morphing element would be travelling towards a target that is itself still
     * moving, and the two animations visibly fight. Pairing a morph with [Zoom] looks broken.
     */
    Morph,
}

internal const val NavMotionKey = "com.vayunmathur.library.util.navMotion"

/** [NavMotion.Detail], the default, so this only needs stating for contrast with its siblings. */
fun DetailPage(): Map<String, Any> = mapOf(NavMotionKey to NavMotion.Detail)

/**
 * [NavMotion.Zoom]: grows out of the tapped item rather than sliding in from the side, which would
 * imply the destination was always over to the right instead of somewhere the user just pointed at.
 */
fun ZoomPage(): Map<String, Any> = mapOf(NavMotionKey to NavMotion.Zoom)

/**
 * [NavMotion.Fullscreen]: no horizontal travel at all. Sliding a full-bleed media surface in from
 * the side draws attention to the edges of a frame meant to be the whole screen, and on a dark
 * viewer it reads as a flicker.
 */
fun FullscreenPage(): Map<String, Any> = mapOf(NavMotionKey to NavMotion.Fullscreen)

/**
 * [NavMotion.Sibling]: deliberately directionless. Peers have no hierarchy, and apps here switch
 * tabs with `backStack.reset(...)`, which nav3 sees as a forward push - so the hierarchical slide
 * would send a tab in from the right even when the user moved *left* along the bar.
 */
fun SiblingPage(): Map<String, Any> = mapOf(NavMotionKey to NavMotion.Sibling)

/**
 * [NavMotion.Morph]: crossfades the screen so that a [sharedContainer], [sharedCrop], [sharedText]
 * or [sharedContent] element is the only thing that appears to move.
 *
 * Use this, never [ZoomPage], on a destination that morphs a component out of the previous screen.
 */
fun MorphPage(): Map<String, Any> = mapOf(NavMotionKey to NavMotion.Morph)

/** The motion the destination asked for, defaulting to [NavMotion.Detail]. */
internal fun Scene<*>.navMotion(): NavMotion = navMotionIn(entries.lastOrNull()?.metadata)

/** The motion declared in a destination's metadata, defaulting to [NavMotion.Detail]. */
internal fun navMotionIn(metadata: Map<String, Any>?): NavMotion =
    metadata?.get(NavMotionKey) as? NavMotion ?: NavMotion.Detail
