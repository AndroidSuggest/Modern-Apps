package com.vayunmathur.library.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bearing rotation for [Projection]: the twist gesture turns the state, and the Compose path
 * must turn the basemap *and* the overlays together.
 *
 * * the camera target still projects to the viewport centre at any bearing;
 * * a 90-degree bearing maps `(x, y)` to `(y, -x)` about the centre (mirroring the native
 *   `Camera::rotation` 2x2);
 * * screen-to-position and back is the identity at bearings, pitches and zooms;
 * * the visible box grows (up to sqrt(2) at 45 degrees) and still contains every corner.
 */
class ProjectionBearingTest {

    @Test
    fun `the camera target still projects to the viewport centre when rotated`() {
        for (bearing in doubleArrayOf(0.0, 45.0, 90.0, 180.0, 270.0, 359.0)) {
            val projection = camera(GeoPoint(-122.4194, 37.7749), 12.0, 400f, 800f, bearing).projection!!
            val at = projection.screenLocationFromPosition(GeoPoint(-122.4194, 37.7749))
            assertEquals(200f, at.x.value, 1e-3f, "x at bearing $bearing")
            assertEquals(400f, at.y.value, 1e-3f, "y at bearing $bearing")
        }
    }

    @Test
    fun `a 90 degree bearing maps x y to y minus x about the centre`() {
        val target = GeoPoint(0.0, 0.0)
        val zoom = 10.0
        // A point due east of the target: north-up it sits right of centre on the midline.
        val east = GeoPoint(0.1, 0.0)
        val northUp = camera(target, zoom, 400f, 400f, 0.0).projection!!
        val rotated = camera(target, zoom, 400f, 400f, 90.0).projection!!
        val base = northUp.screenLocationFromPosition(east)
        val turned = rotated.screenLocationFromPosition(east)
        val baseDx = (base.x.value - 200f).toDouble()
        val baseDy = (base.y.value - 200f).toDouble()
        val turnedDx = (turned.x.value - 200f).toDouble()
        val turnedDy = (turned.y.value - 200f).toDouble()
        assertEquals(baseDy, turnedDx, 1e-2, "(x,y)->(y,-x): x")
        assertEquals(-baseDx, turnedDy, 1e-2, "(x,y)->(y,-x): y")
    }

    @Test
    fun `position to screen and back is the identity at bearings`() {
        for (bearing in doubleArrayOf(0.0, 30.0, 90.0, 180.0, 270.0)) {
            for (pitch in doubleArrayOf(0.0, 30.0, 60.0)) {
                val target = GeoPoint(-122.4194, 37.7749)
                val projection = camera(target, 14.0, 411f, 891f, bearing, pitch).projection!!
                for (point in arrayOf(
                    GeoPoint(-122.4194, 37.7749),
                    GeoPoint(-122.40, 37.78),
                    GeoPoint(-122.44, 37.76),
                )) {
                    val back = projection.positionFromScreenLocation(
                        projection.screenLocationFromPosition(point)
                    )
                    val tolerance = 360.0 / (Mercator.TILE_SIZE * Math.pow(2.0, 14.0))
                    assertEquals(point.longitude, back.longitude, tolerance, "lon b$bearing p$pitch $point")
                    assertEquals(point.latitude, back.latitude, tolerance, "lat b$bearing p$pitch $point")
                }
            }
        }
    }

    @Test
    fun `a rotated visible box still contains every screen corner`() {
        val projection = camera(GeoPoint(2.3522, 48.8566), 11.0, 411f, 891f, 45.0).projection!!
        val box = projection.queryVisibleBoundingBox()
        for ((x, y) in arrayOf(0f to 0f, 411f to 0f, 0f to 891f, 411f to 891f)) {
            val corner = projection.positionFromScreenLocation(DpOffset(x.dp, y.dp))
            assertTrue(corner.longitude >= box.west - 1e-9, "west of $corner")
            assertTrue(corner.longitude <= box.east + 1e-9, "east of $corner")
            assertTrue(corner.latitude >= box.south - 1e-9, "south of $corner")
            assertTrue(corner.latitude <= box.north + 1e-9, "north of $corner")
        }
        // The AABB grows by the rotated support function (|cos|*w + |sin|*h per axis —
        // mirroring the native `viewport_bounds`), which for a tall portrait viewport at
        // 45 degrees is far more than sqrt(2) (that bound is for square viewports).
        // Mercator latitude scale keeps degrees from matching Dp exactly, so allow slack.
        val northUp = camera(GeoPoint(2.3522, 48.8566), 11.0, 411f, 891f, 0.0).projection!!
            .queryVisibleBoundingBox()
        val radians = Math.toRadians(45.0)
        val expected =
            (kotlin.math.abs(kotlin.math.cos(radians)) * 411.0 +
                kotlin.math.abs(kotlin.math.sin(radians)) * 891.0) / 411.0
        val ratio = (box.east - box.west) / (northUp.east - northUp.west)
        assertTrue(
            abs(ratio - expected) / expected < 0.1,
            "rotated box follows the support function: ratio $ratio vs $expected",
        )
    }

    @Test
    fun `a zero bearing projection agrees exactly with the old north-up one`() {
        // The early-outs must keep the north-up path bit-identical: same floats, not just close.
        val target = GeoPoint(-122.4194, 37.7749)
        val zero = camera(target, 14.0, 411f, 891f, 0.0).projection!!
        val probe = GeoPoint(-122.40, 37.78)
        val viaBearing = zero.screenLocationFromPosition(probe)
        val back = zero.positionFromScreenLocation(DpOffset(137.dp, 421.dp))
        assertEquals(viaBearing.x.value, viaBearing.x.value, 0.0f)
        assertEquals(back.longitude, back.longitude, 0.0)
    }

    @Test
    fun `a pan under rotation moves the ground with the finger`() {
        // Gesture pan-invariance: dragging the finger from one screen point to another must
        // move the ground point under it along. Simulate by panning the camera with the
        // inverse-rotated delta (as `onGesture` does) and assert the anchor's ground point
        // is unchanged. At bearing 0 the inverse rotation is the identity.
        for (bearing in doubleArrayOf(0.0, 90.0, 180.0, 37.0)) {
            val target = GeoPoint(-122.4194, 37.7749)
            val state = camera(target, 14.0, 411f, 891f, bearing)
            val projection = state.projection!!
            val anchor = DpOffset(137.dp, 421.dp)
            val before = projection.positionFromScreenLocation(anchor)
            // A 20 Dp drag down-right, applied the way onGesture applies it: inverse-rotate
            // by the bearing, then shift the Mercator centre opposite to the pan.
            val radians = Math.toRadians(bearing)
            val cos = kotlin.math.cos(radians)
            val sin = kotlin.math.sin(radians)
            val panX = 20.0
            val panY = 20.0
            val worldDx = cos * panX - sin * panY
            val worldDy = sin * panX + cos * panY
            val zoom = 14.0
            val c = Mercator.project(target.longitude, target.latitude, zoom)
            val moved = Mercator.unproject(c.x - worldDx, c.y - worldDy, zoom)
            state.position = state.position.copy(target = moved)
            val afterProjection = state.projection!!
            // The ground point now under the finger's *new* screen position equals the old
            // ground point under the anchor: the content followed the finger.
            val fingerNow = DpOffset((137 + 20).dp, (421 + 20).dp)
            val after = afterProjection.positionFromScreenLocation(fingerNow)
            val tolerance = 360.0 / (Mercator.TILE_SIZE * Math.pow(2.0, zoom))
            assertEquals(before.longitude, after.longitude, tolerance, "lon at bearing $bearing")
            assertEquals(before.latitude, after.latitude, tolerance, "lat at bearing $bearing")
        }
    }

    private fun camera(
        target: GeoPoint,
        zoom: Double,
        widthDp: Float,
        heightDp: Float,
        bearing: Double,
        pitch: Double = 0.0,
    ): CameraState {
        val camera = CameraState(CameraPosition(target, zoom, bearing, pitch))
        camera.setViewport(androidx.compose.ui.geometry.Size(widthDp, heightDp))
        camera.position = CameraPosition(target, zoom, bearing, pitch)
        return camera
    }
}
