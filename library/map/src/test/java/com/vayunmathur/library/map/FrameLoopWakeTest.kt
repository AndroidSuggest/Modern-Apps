package com.vayunmathur.library.map

import android.app.Application
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The on-demand frame loop's wake contract.
 *
 * The renderer draws on demand rather than every vsync, so the cost of failing to notice a
 * change is a map that has **silently stopped updating** — far worse than a wasted frame. Every
 * mutator on [SurfaceMapRenderer] must therefore wake the loop through [SurfaceMapRenderer.invalidate],
 * and the loop must then hold itself open for [SurfaceMapRenderer.IDLE_GRACE_NANOS] to cover a
 * wake that arrives a frame or two late.
 *
 * Each test below enumerates one half of that contract explicitly, so a change to either half
 * fails here rather than freezing the map on device:
 *
 * - every public mutator wakes the loop ([SurfaceMapRenderer.wakeCount] moves);
 * - [SurfaceMapRenderer.stop], [SurfaceMapRenderer.destroy] and
 *   [SurfaceMapRenderer.detachSurface] deliberately do *not* (they park the loop, not wake it);
 * - the idle grace is exactly 250 ms.
 *
 * The remaining wake sources live outside this class and are covered where they live: the two
 * `snapshotFlow` collectors in `VulkanMapSurface` (camera + viewport), the camera-diff backstop
 * inside `renderFrame`, and the native `nextFrameDelayMillis` arms (tiles in flight, upload
 * drain, re-tessellation, swapchain rebuild, buffer grace, LOD fade, retry backoff). Those need
 * a surface or a GPU and cannot run on the JVM.
 *
 * Runs on plain JVM unit tests with no Robolectric: with no surface attached (`handle == 0`)
 * `requestFrame` returns before touching `Choreographer`, so waking is observable purely as
 * [SurfaceMapRenderer.wakeCount]. `Application()` is enough of a `Context` for that — the cache
 * dir is only resolved inside `attachSurface`, which needs a real surface and is not exercised
 * here.
 */
class FrameLoopWakeTest {

    private fun renderer() = SurfaceMapRenderer(Application(), density = 3f)

    /** One wake per mutator: the enumerated Kotlin-side wake sources. */
    @Test
    fun every_mutator_wakes_the_loop() {
        val wakes = mutableListOf<String>()
        fun check(name: String, block: SurfaceMapRenderer.() -> Unit) {
            val r = renderer()
            val before = r.wakeCount
            r.block()
            assertTrue(
                r.wakeCount > before,
                "$name did not wake the frame loop; the map would silently stop updating",
            )
            wakes += name
        }

        check("camera setter") { camera = CameraPosition() }
        check("composeCamera setter") { composeCamera = CameraState() }
        check("start") { start() }
        check("resize") { resize(100, 100) }
        check("setPalette") { setPalette(dark = true, muted = false) }
        check("setLayers") { setLayers(LayerOptions()) }
        check("setTrafficSpeeds") { setTrafficSpeeds(longArrayOf(), intArrayOf()) }
        check("clearTraffic") { clearTraffic() }
        check("setOnline") { setOnline(false) }
        check("setUserPuck") { setUserPuck(null) }
        check("setMarkers") { setMarkers(emptyList()) }
        check("setVehicles") { setVehicles(emptyList()) }
        check("setRegionMask") { setRegionMask(null) }
        check("setRoute(points)") { setRoute(points = null) }
        check("setRoute(overlay)") { setRoute(overlay = null) }
        check("invalidate") { invalidate() }

        assertEquals(16, wakes.size, "the enumeration itself must stay complete: $wakes")
    }

    /** Parking the loop is not waking it: stopping, destroying or detaching owes no frame. */
    @Test
    fun parking_the_loop_wakes_nothing() {
        val r = renderer()
        r.start()
        val parked = r.wakeCount
        r.stop()
        r.detachSurface()
        r.destroy()
        assertEquals(parked, r.wakeCount, "stop/detach/destroy must not wake the loop")
    }

    /** The idle grace is exactly a quarter of a second. */
    @Test
    fun the_idle_grace_is_a_quarter_second() {
        assertEquals(
            TimeUnit.MILLISECONDS.toNanos(250),
            SurfaceMapRenderer.IDLE_GRACE_NANOS,
            "the grace covers a late wake without re-heating the phone",
        )
    }
}
