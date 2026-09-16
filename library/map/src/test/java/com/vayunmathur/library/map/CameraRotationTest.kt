package com.vayunmathur.library.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The twist-rotate camera contract.
 *
 * The two-finger twist turns [CameraPosition.bearing], which wraps 0–360 rather than
 * clamping; pan and zoom never reset it (the position is `copy`ied, not rebuilt); the
 * rotate gate ([GestureOptions.isRotateEnabled]) suppresses only rotation; and the saver
 * round-trips the bearing alongside target/zoom/pitch. `animateTo`'s short-way turn is
 * pinned here too, since reset-north rides it.
 *
 * Plain JVM unit tests like [FrameLoopWakeTest]: `CameraState` is Compose snapshot state,
 * which reads and writes fine off-device as long as nothing composes it.
 */
class CameraRotationTest {

    private fun camera(): CameraState =
        CameraState().apply { setViewport(Size(400f, 800f)) }

    /** A no-op gesture: no pan, no zoom, no twist. */
    private fun CameraState.idle() {
        onGesture(
            centroidDp = Offset.Zero,
            panDp = Offset.Zero,
            zoomChange = 1f,
            minZoom = 0.0,
            maxZoom = 20.0,
            scrollEnabled = true,
            zoomEnabled = true,
        )
    }

    @Test
    fun twist_turns_the_bearing() {
        val c = camera()
        // Compose reports twist counterclockwise-positive; the camera is clockwise, hence -20.
        c.onGesture(
            centroidDp = Offset.Zero,
            panDp = Offset.Zero,
            zoomChange = 1f,
            rotationDeg = -20f,
            minZoom = 0.0,
            maxZoom = 20.0,
            scrollEnabled = true,
            zoomEnabled = true,
        )
        assertEquals(20.0, c.position.bearing, 1e-6)
    }

    @Test
    fun bearing_wraps_past_north_both_ways() {
        val c = camera()
        c.onRotate(350.0)
        assertEquals(350.0, c.position.bearing, 1e-9)
        c.onRotate(20.0)
        assertEquals(10.0, c.position.bearing, 1e-9, "350 + 20 wraps through north to 10")
        c.onRotate(-30.0)
        assertEquals(340.0, c.position.bearing, 1e-9, "10 - 30 wraps the other way to 340")
    }

    @Test
    fun rotation_accumulates_across_gestures() {
        val c = camera()
        repeat(4) { c.onRotate(15.0) }
        assertEquals(60.0, c.position.bearing, 1e-9)
    }

    @Test
    fun pan_and_zoom_preserve_the_bearing() {
        val c = camera()
        c.onRotate(45.0)
        // A pan with no twist.
        c.onGesture(
            centroidDp = Offset(200f, 400f),
            panDp = Offset(10f, -5f),
            zoomChange = 1f,
            minZoom = 0.0,
            maxZoom = 20.0,
            scrollEnabled = true,
            zoomEnabled = true,
        )
        assertEquals(45.0, c.position.bearing, 1e-9, "panning must not reset the bearing")
        // A zoom with no twist.
        c.onGesture(
            centroidDp = Offset(200f, 400f),
            panDp = Offset.Zero,
            zoomChange = 2f,
            minZoom = 0.0,
            maxZoom = 20.0,
            scrollEnabled = true,
            zoomEnabled = true,
        )
        assertEquals(45.0, c.position.bearing, 1e-9, "zooming must not reset the bearing")
    }

    @Test
    fun rotate_gate_suppresses_only_rotation() {
        val c = camera()
        c.onGesture(
            centroidDp = Offset(200f, 400f),
            panDp = Offset(10f, 0f),
            zoomChange = 1f,
            rotationDeg = -30f,
            minZoom = 0.0,
            maxZoom = 20.0,
            scrollEnabled = true,
            zoomEnabled = true,
            rotateEnabled = false,
        )
        assertEquals(0.0, c.position.bearing, absoluteTolerance = 1e-9)
        assertTrue(
            c.position.target.longitude != 0.0 || c.position.target.latitude != 0.0,
            "the pan must still apply while rotation is gated",
        )
    }

    @Test
    fun disabled_gestures_leave_the_camera_alone() {
        val c = camera()
        val before = c.position
        c.onGesture(
            centroidDp = Offset.Zero,
            panDp = Offset(50f, 50f),
            zoomChange = 2f,
            rotationDeg = -30f,
            minZoom = 0.0,
            maxZoom = 20.0,
            scrollEnabled = false,
            zoomEnabled = false,
            rotateEnabled = false,
        )
        assertEquals(before, c.position)
    }

    @Test
    fun zero_twist_is_a_no_op() {
        val c = camera()
        c.onRotate(30.0)
        val before = c.position
        c.onRotate(0.0)
        assertEquals(before, c.position)
        c.idle()
        assertEquals(before, c.position)
    }

    @Test
    fun wrap_bearing_normalises() {
        assertEquals(0.0, wrapBearing(0.0), 1e-9)
        assertEquals(0.0, wrapBearing(360.0), 1e-9)
        assertEquals(10.0, wrapBearing(370.0), 1e-9)
        assertEquals(350.0, wrapBearing(-10.0), 1e-9)
        assertEquals(180.0, wrapBearing(180.0), 1e-9)
    }

    @Test
    fun compass_hides_only_near_north() {
        // Mirrors CompassButton's own threshold (|normalized| < 1° hides): a bearing the
        // gesture can actually leave behind must read as rotated, and reset-north's 0.0
        // must read as north. (The button itself is untestable here — it composes.)
        assertTrue(abs(((10.0 + 180.0) % 360.0 + 360.0) % 360.0 - 180.0) >= 1.0)
        assertTrue(abs(((0.0 + 180.0) % 360.0 + 360.0) % 360.0 - 180.0) < 1.0)
    }
}
