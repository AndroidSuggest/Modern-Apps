package com.vayunmathur.auto.platform

import android.os.Handler
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * Head-unit input injection for one [CarDisplay].
 *
 * Split from the display for the 25-function cap: the display keeps creation,
 * feeds and teardown while this owns the whole touch/key/scroll path. Reads
 * the live decor view through [decorView] (null before the presentation is
 * up) and the card bounds through [cardBounds]; taps funnel through
 * [onCardTap]. All entry points are safe from any thread and hop to
 * [mainHandler] because views may only be touched there.
 */
internal class CarDisplayInput(
    private val mainHandler: Handler,
    private val decorView: () -> View?,
    private val cardBounds: () -> CarDisplay.TapBounds?,
    private val onCardTap: () -> Unit,
) {
    /**
     * Injects one head-unit touch frame into the car UI. Safe from any thread.
     *
     * [pointers] are display pixels (what ch8 scales to): (x, y, pointer id).
     * [action] is the `MotionEvent` action verbatim -- the head unit numbers
     * touch actions exactly as `MotionEvent` does -- with the pointer index
     * for POINTER_DOWN/UP shifted in at build time. Posts to the main thread
     * because views may only be touched there; order is preserved (one FIFO
     * queue, posted in arrival order), so down/move/up stay a gesture. Returns
     * false when the presentation is not up yet.
     */
    fun injectTouch(action: Int, pointers: List<Triple<Float, Float, Int>>, actionIndex: Int): Boolean {
        if (decorView() == null) return false
        mainHandler.post { dispatchTouch(action, pointers, actionIndex) }
        return true
    }

    /**
     * Injects one head-unit key press or release. Safe from any thread; the
     * dispatch hops to main like [injectTouch]. Returns false with no UI up.
     */
    fun injectKey(keycode: Int, down: Boolean): Boolean {
        val view = decorView() ?: return false
        mainHandler.post {
            val event = KeyEvent(
                if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                keycode,
            )
            view.dispatchKeyEvent(event)
        }
        return true
    }

    /**
     * Injects one head-unit scroll tick. Safe from any thread; the dispatch
     * hops to main like [injectTouch]. Returns false with no UI up.
     */
    fun injectScroll(delta: Int): Boolean {
        val view = decorView() ?: return false
        mainHandler.post {
            val now = android.os.SystemClock.uptimeMillis()
            val coords = MotionEvent.PointerCoords().apply {
                setAxisValue(MotionEvent.AXIS_VSCROLL, delta.toFloat())
            }
            val event = MotionEvent.obtain(
                now, now,
                MotionEvent.ACTION_SCROLL,
                SINGLE_POINTER,
                arrayOf(MotionEvent.PointerProperties().apply { id = 0 }),
                arrayOf(coords),
                0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_MOUSE, 0,
            )
            view.dispatchTouchEvent(event)
            event.recycle()
        }
        return true
    }

    /**
     * Routes a head-unit tap at the now-playing card.
     *
     * [x] and [y] are display pixels (what ch8 scales to), compared against the
     * card's on-screen bounds on the virtual display. Returns whether the tap
     * hit the card; a hit posts the toggle to the main thread because views may
     * only be touched there. Called from [dispatchTouch] for single-pointer
     * DOWN inside the card bounds -- previously zero callers, now the card-tap
     * path for ch8 touch.
     */
    fun handleCarTap(x: Float, y: Float): Boolean {
        val bounds = cardBounds() ?: return false
        if (!bounds.contains(x, y)) return false
        mainHandler.post { onCardTap() }
        return true
    }

    /**
     * `downTime` of the gesture in progress, for the synthesized touch stream.
     * Main thread only: written and read inside the posted [dispatchTouch].
     */
    private var gestureDownTime: Long = 0

    /** Main thread only: builds one multi-pointer event and dispatches it. */
    private fun dispatchTouch(
        action: Int,
        pointers: List<Triple<Float, Float, Int>>,
        actionIndex: Int,
    ) {
        val view = decorView() ?: return
        // A tap on the now-playing card toggles playback directly: the tap
        // coordinates are display pixels and the card bounds are too, so a
        // DOWN inside them is an unambiguous card hit. Other gestures (and
        // taps elsewhere) still dispatch normally so app tiles stay tappable.
        if (action == MotionEvent.ACTION_DOWN && pointers.size == SINGLE_POINTER) {
            val (x, y, _) = pointers[0]
            if (handleCarTap(x, y)) return
        }
        val now = android.os.SystemClock.uptimeMillis()
        val downTime = if (action == MotionEvent.ACTION_DOWN) {
            gestureDownTime = now
            now
        } else {
            gestureDownTime
        }
        val fullAction = when (action) {
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP,
            -> action or (actionIndex.coerceIn(0, pointers.size - 1) shl
                MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            else -> action
        }
        val props = pointers.map { (_, _, id) ->
            MotionEvent.PointerProperties().apply { this.id = id }
        }.toTypedArray()
        val coords = pointers.map { (x, y, _) ->
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
            }
        }.toTypedArray()
        val event = MotionEvent.obtain(
            downTime, now, fullAction, pointers.size, props, coords,
            0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        view.dispatchTouchEvent(event)
        event.recycle()
        if (action == MotionEvent.ACTION_UP ||
            action == MotionEvent.ACTION_CANCEL
        ) {
            gestureDownTime = 0
        }
    }

    private companion object {
        /** Pointer count of a single-finger gesture (tap, scroll tick). */
        const val SINGLE_POINTER = 1
    }
}
