package com.vayunmathur.library.ui

import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import kotlin.math.abs

/**
 * The sideways branch of [reorderGridDragHandle]: what a horizontal drag means when it is *not* a
 * reorder — dismissing the tile, typically.
 *
 * [enabled] is asked once the drag has started, so a caller can turn the branch off for the state
 * where the flick would be meaningless or destructive. [onDrag] gets the accumulated travel so the
 * tile can be translated under the finger, and [onRelease] the travel and the fling velocity
 * together, so a short fast flick and a long slow drag can both count as the same intent.
 */
class HorizontalFlick(
    val enabled: () -> Boolean = { true },
    val onDrag: (totalDx: Float) -> Unit,
    val onRelease: (totalDx: Float, velocity: Float) -> Unit,
)

/** How a press ended: with the long-press timeout, or with the finger moving or leaving first. */
internal class GridPress(
    val longPressed: Boolean,
    val moved: Boolean,
    val velocity: VelocityTracker,
    val totalDx: Float,
    val totalDy: Float,
)

/**
 * Races the long-press timeout against the finger moving, and consumes nothing either way — until
 * the timeout expires there is no way to tell a pick-up from a tap, a sideways flick or a scroll.
 *
 * The travel and velocity so far come back with it, so a flick that grows out of the press does not
 * restart its axis test from wherever the finger was when the race ended.
 */
internal suspend fun AwaitPointerEventScope.awaitGridPress(
    down: PointerInputChange,
    slop: Float,
    longPressMillis: Long,
): GridPress {
    val velocity = VelocityTracker()
    // `addPointerInputChange`, not `addPosition`: a fast flick arrives as very few events, each
    // carrying several historical samples, and only this overload feeds them all in. One sample per
    // event leaves a flick's velocity badly under-estimated.
    velocity.addPointerInputChange(down)
    var totalDx = 0f
    var totalDy = 0f
    var moved = false

    val longPressed = try {
        withTimeout(longPressMillis) {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                totalDx += change.position.x - change.previousPosition.x
                totalDy += change.position.y - change.previousPosition.y
                velocity.addPointerInputChange(change)
                if (!change.pressed) break
                if (abs(totalDx) > slop || abs(totalDy) > slop) {
                    moved = true
                    break
                }
            }
        }
        false
    } catch (_: PointerEventTimeoutCancellationException) {
        true
    }

    return GridPress(longPressed, moved, velocity, totalDx, totalDy)
}

/**
 * Follows a drag that might be a sideways flick, claiming it only once it is clearly horizontal.
 *
 * Vertical winning returns without having consumed anything, which is what leaves the grid free to
 * scroll; the caller is left with a gesture it never touched.
 */
internal suspend fun AwaitPointerEventScope.trackHorizontalFlick(
    down: PointerInputChange,
    slop: Float,
    horizontalBias: Float,
    flick: HorizontalFlick,
    press: GridPress,
) {
    val velocity = press.velocity
    var claimed = false
    var totalDx = press.totalDx
    var totalDy = press.totalDy

    // The travel so far may already settle the axis, and there is no guarantee of another event
    // before the finger lifts.
    fun vertical(): Boolean = abs(totalDy) > slop && abs(totalDy) >= abs(totalDx)
    fun horizontal(): Boolean = abs(totalDx) > slop && abs(totalDx) > abs(totalDy) * horizontalBias

    if (vertical()) return
    if (horizontal()) {
        claimed = true
        flick.onDrag(totalDx)
    }

    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        totalDx += change.position.x - change.previousPosition.x
        totalDy += change.position.y - change.previousPosition.y
        velocity.addPointerInputChange(change)

        if (!claimed) {
            if (!change.pressed) break
            if (vertical()) return
            if (!horizontal()) continue
            claimed = true
        }

        change.consume()
        flick.onDrag(totalDx)
        if (!change.pressed) break
    }

    if (claimed) flick.onRelease(totalDx, velocity.calculateVelocity().x)
}
