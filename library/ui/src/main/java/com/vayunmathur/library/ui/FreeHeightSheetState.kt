package com.vayunmathur.library.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * State for [FreeHeightBottomSheetScaffold]: a single [Animatable] holding the
 * sheet's **visible height in pixels**, and nothing else.
 *
 * The sheet rests at whatever height the user leaves it at. There is a peek and an
 * expanded bound, but they are limits rather than anchors — every height between them
 * is a valid resting place, and a fling decays to a stop rather than snapping to one
 * end or the other.
 *
 * One source of truth is what makes that possible. Material 3's `SheetState` is a
 * closed `SheetValue` enum whose `PartiallyExpanded` is hard-pinned to a fixed
 * `sheetPeekHeight` dp, so no arbitrary height is expressible; here every height is
 * just a value. Neither limit is a fixed dp either: the peek is measured off the
 * sheet's own header and the expanded bound off its content.
 *
 * [SheetValue.Hidden] is reachable only through [hide]. A drag clamps at the peek
 * and [Animatable.updateBounds] makes a fling decay stop there too, so
 * "the user cannot dismiss this sheet" is enforced by construction rather than by
 * vetoing state changes after the fact.
 *
 * The upper bound is the lesser of the space available and the sheet content's
 * own height, so a sheet holding two lines of text does not stretch to fill the
 * window. Content height is learned by observation — whenever the content
 * measures shorter than the height it was offered, that shorter height becomes
 * the ceiling — because the content typically ends in a `LazyColumn`, whose
 * intrinsic height cannot be queried. The observation is discarded via
 * [resetContentCap] when the host swaps in different content.
 */
@Stable
class FreeHeightSheetState(private val initialValue: SheetValue) {
    /** Visible sheet height, in pixels. `0` is hidden. */
    private val offset = Animatable(0f)

    private var peekPx = 0f
    private var expandedPx = 0f
    /** Space the host can give the sheet, before the content ceiling applies. */
    private var availablePx = 0f
    /** Tallest the content has been seen to need. [Float.MAX_VALUE] = not yet known. */
    private var contentCapPx = Float.MAX_VALUE
    /**
     * False until the host has measured itself and the bounds mean anything. A
     * snapshot state, because a programmatic [expand] can arrive before the first
     * layout pass (a screen restoring its selection) and must wait rather than be
     * dropped.
     */
    private var measured by mutableStateOf(false)

    /**
     * How many host-driven animations — [hide], [expand], [partialExpand] — are in flight.
     *
     * Two things defer to this. [flingBy], because a fling is the tail of a gesture that may
     * already be over: one whose `onPostFling` lands a frame after the host called
     * [partialExpand] would otherwise cancel it and coast off from wherever it had got to. And
     * [armBounds], because a running animation owns the height and a floor installed underneath
     * it does not merely slow it — `Animatable` re-clamps every frame and ends the animation with
     * `BoundReached`, stopping it dead. A [hide] parked at the peek showing empty content is the
     * worst version of that, but the same applies to a sheet being lifted to a peek that just
     * grew. Two callers re-arm during exactly those frames: [resetContentCap], fired by the host
     * swapping content out, and [onContentHeight], fired by the layout pass once that content
     * measures smaller.
     *
     * A count rather than a flag because the three cancel *each other*, and the loser's `finally`
     * runs while the winner is still animating. A boolean would be cleared by that cleanup and
     * leave the winner unprotected for the rest of its run. [growBy] does not clear this: its
     * `snapTo` cancels the animation, and that cancellation decrements the count through the same
     * `finally`. It does not need to — the drag path coerces into `[peekPx, expandedPx]` itself,
     * so a temporarily absent floor cannot let a drag escape below the peek.
     */
    private var hostAnimations = 0

    private val hostAnimating: Boolean
        get() = hostAnimations > 0

    internal val offsetPx: Float
        get() = offset.value

    /**
     * How far above its resting place map chrome must sit to clear the sheet, in
     * pixels. `0` while the sheet is hidden.
     *
     * **This is a height, not a distance from the top of the window** — the opposite
     * of Material's `SheetState.requireOffset`, so a caller offsets by the *negation*
     * of it (`IntOffset(0, -lift)`) and needs no baseline to difference against.
     *
     * There is also nothing to sample a baseline from: this sheet has no anchors, so
     * a fling rests wherever the decay stops and there is no settled "peek" position.
     * The value is read inside a layout lambda rather than observed, so chrome tracks
     * the sheet every frame without recomposing.
     */
    val liftPx: Float
        get() = offset.value

    internal val expandedHeightPx: Float
        get() = expandedPx

    /**
     * Take the host's measurements. Called whenever the container height or the
     * peek height changes — a rotation, a keyboard, a newly measured header — so the
     * bounds and the current height stay consistent with what is on screen.
     */
    internal suspend fun onMeasured(peek: Float, expanded: Float) {
        availablePx = expanded
        applyBounds(peek)
        if (!measured) {
            offset.snapTo(
                when (initialValue) {
                    SheetValue.Hidden -> 0f
                    SheetValue.PartiallyExpanded -> peekPx
                    else -> expandedPx
                }
            )
            // Last, so anything waiting in `awaitMeasured` resumes to a sheet that is
            // already at its initial height with its bounds armed.
            armBounds()
            measured = true
        } else if (offset.value > 0f && offset.value < peekPx && !hostAnimating) {
            // The peek grew out from under a sheet that was resting below it — the usual cause
            // being a measured header that gained a row once async content arrived, which for a
            // place sheet is the tab row appearing a beat after the user opened the POI.
            //
            // `armBounds` alone would handle it, but by teleporting: `updateBounds` clamps the
            // current value into the new bounds synchronously, so the sheet jumps by the height
            // of whatever appeared. Animating there instead makes it read as the sheet growing
            // to fit its content, which is what actually happened.
            animateToHeight { peekPx }
        } else {
            // `updateBounds` re-clamps, so a screen that just got shorter is pulled
            // back inside the new bounds here. A jump is right for that one: a rotation
            // or a keyboard relays out everything on screen at once anyway.
            armBounds()
        }
    }

    /**
     * Derive [expandedPx] and [peekPx] from the available space and the content
     * ceiling. The peek is clamped rather than used as a floor: content shorter
     * than the peek should shrink the sheet, not leave dead space below it.
     */
    private fun applyBounds(peek: Float) {
        expandedPx = availablePx.coerceAtMost(contentCapPx).coerceAtLeast(0f)
        peekPx = peek.coerceAtMost(expandedPx)
    }

    /**
     * Report what the sheet content actually measured when offered `offered`
     * pixels. Slack means the content is fully visible and needs no more room, so
     * `measured` becomes the new ceiling; filling the offer says nothing about how
     * much taller it might be, so the ceiling is left alone and re-probed as the
     * user drags further up.
     */
    internal fun onContentHeight(contentHeight: Float, offered: Float, scope: CoroutineScope) {
        if (!measured || contentHeight <= 0f || contentHeight >= offered) return
        if (contentHeight == contentCapPx) return
        contentCapPx = contentHeight
        applyBounds(peekPx)
        if (offset.value > expandedPx) {
            scope.launch { offset.snapTo(expandedPx) }
        }
        armBounds()
    }

    /**
     * Forget the learned content ceiling, so the next layout re-probes it. The
     * host calls this when it swaps in different content, which may well be
     * taller than whatever the last content settled on.
     */
    internal fun resetContentCap(peek: Float) {
        contentCapPx = Float.MAX_VALUE
        applyBounds(peek)
        armBounds()
    }

    private suspend fun awaitMeasured() {
        if (!measured) {
            snapshotFlow { measured }.first { it }
        }
    }

    /**
     * Clamp drags and fling decay at the peek, unless the sheet is hidden or a host-driven
     * animation is in flight — see [hostAnimations] for why those must not have a floor put
     * under them.
     *
     * The floor never rises above where the sheet already is. Raising it is the one thing
     * `updateBounds` does that the user can see: it clamps synchronously, so a peek that grew
     * would teleport a resting sheet up by however much it grew. [onMeasured] animates that
     * move instead, and this coerce is what stops whoever re-arms first from doing it abruptly
     * beforehand — [resetContentCap] shares a key with [onMeasured] and would otherwise race it.
     */
    private fun armBounds() {
        val floor = if (hostAnimating || offset.value <= 0f) 0f else peekPx.coerceAtMost(offset.value)
        offset.updateBounds(floor.coerceAtMost(expandedPx), expandedPx)
    }

    /** Raise the sheet to its full height. */
    suspend fun expand() = animateToHeight { expandedPx }

    /** Drop the sheet back to its peek height. */
    suspend fun partialExpand() = animateToHeight { peekPx }

    /**
     * Dismiss the sheet. The only way to reach a zero height: the drag and fling
     * paths both floor at the peek.
     */
    suspend fun hide() {
        awaitMeasured()
        hostAnimations++
        try {
            offset.updateBounds(0f, expandedPx)
            offset.animateTo(0f)
        } finally {
            // Decremented even on cancellation — a hide interrupted by the user grabbing the
            // sheet must hand a normal peek floor back to the drag path.
            hostAnimations--
            armBounds()
        }
    }

    /**
     * `target` is a lambda because the height it names is not known until
     * [onMeasured] has run, which [awaitMeasured] may be waiting for.
     */
    private suspend fun animateToHeight(target: () -> Float) {
        awaitMeasured()
        hostAnimations++
        try {
            // Widen the floor so the animation can leave a hidden sheet, then re-arm it
            // on the way out — including on cancellation, when the user grabs the handle
            // mid-flight and the drag path takes over.
            //
            // Inside the `try`, matching [hide]: it is the only statement between the
            // increment above and the animation that could throw, and if it escaped the
            // count would never come back down. Nothing here enforces the invariant that
            // makes it safe — `expandedPx` is coerced non-negative over in [applyBounds] —
            // and the cost of it being wrong is silent, since a stuck count does not crash,
            // it just quietly costs the sheet its peek floor and its fling for good.
            offset.updateBounds(0f, expandedPx)
            offset.animateTo(target())
        } finally {
            hostAnimations--
            armBounds()
        }
    }

    /**
     * Grow the sheet by `growth` pixels (negative shrinks), returning how much of
     * that the sheet actually absorbed. Callers use the difference to decide how
     * much of a gesture to report as consumed.
     */
    internal fun growBy(growth: Float, scope: CoroutineScope): Float {
        if (!measured || offset.value <= 0f) return 0f
        // `hostAnimations` is deliberately not touched here even though the user taking hold of
        // the sheet supersedes whatever the host was animating: the `snapTo` below cancels that
        // animation and the cancellation decrements the count through its own `finally`.
        // Decrementing here as well would drive it negative and disarm the guard for good. The
        // window before that cancellation lands is harmless, because the coerce below is what
        // keeps a drag inside the bounds, not the floor.
        val target = (offset.value + growth).coerceIn(peekPx, expandedPx)
        val applied = target - offset.value
        if (applied != 0f) {
            // A new snapTo cancels whatever animation was running, which is exactly
            // what grabbing the sheet mid-fling should do.
            scope.launch { offset.snapTo(target) }
        }
        return applied
    }

    /**
     * Fling the sheet, returning the velocity it could **not** absorb. There is
     * deliberately no settle-to-nearest-anchor afterwards: wherever the decay stops
     * is where the sheet stays.
     *
     * Unlike [growBy] this does **not** supersede a host-driven animation, because it is
     * dispatched at the end of every gesture rather than only when the user is holding
     * the sheet — see [hostAnimating].
     */
    internal suspend fun flingBy(velocity: Float): Float {
        if (!measured || hostAnimating || offset.value <= 0f) return velocity
        armBounds()
        return offset.animateDecay(velocity, exponentialDecay()).endState.velocity
    }
}

@Composable
fun rememberFreeHeightSheetState(
    initialValue: SheetValue = SheetValue.PartiallyExpanded,
): FreeHeightSheetState = remember { FreeHeightSheetState(initialValue) }
