package com.vayunmathur.auto.platform

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.vayunmathur.auto.protocol.NavSnapshot
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.LayerOptions
import com.vayunmathur.library.map.MapRenderState
import com.vayunmathur.library.map.SurfaceMapRenderer
import com.vayunmathur.library.map.UserPuck

/**
 * Draws the phone map into the car video stream (Phase 6 mirror path).
 *
 * This is the short-term Maps answer from the plan: with no gearhead host
 * binding, MA Auto renders the `:library:map` basemap itself -- same
 * renderer, archive and tile cache the phone map uses -- onto an offscreen
 * [Surface] whose frames join the ch2 path, while Phase 2 touch pans it.
 *
 * Two surfaces, two renderers, one map state: the encoder keeps its own
 * input surface (the `CarDisplay` virtual display composites into it), and
 * this mirror draws into [mirrorSurface] with its own `SurfaceMapRenderer`.
 * The service composites or switches between them per the session's
 * [com.vayunmathur.auto.protocol.MapsPath] -- that composition is the
 * bring-up owner's work (see the Phase 6 handoff to the service owner in
 * `auto/docs/HANDOFF.md` §10), not this file's: this file only draws a map
 * into a surface it is handed.
 *
 * Threading follows `SurfaceMapRenderer`'s own rule: every call on the main
 * thread. [render] and [setSurface] marshal onto [mainHandler] so the
 * guidance monitor's worker-thread snapshots stay safe; destruction is
 * synchronous on main like `CarMapRenderer.teardown`.
 *
 * Route identity (not equality) gates the route push, matching
 * `NavMapScreen.pushedRoute`: the native side tessellates on every `set`
 * call, so pushing an equal-but-new list per GPS fix would re-tessellate a
 * cross-city route a few times a second.
 */
class CarMapsMirror(
    context: Context,
    private val density: Float = 1f,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var renderer: SurfaceMapRenderer? = null
    private var mirrorSurface: Surface? = null
    private var mirrorWidth = 0
    private var mirrorHeight = 0

    /** The route polyline already on the GPU, by identity. */
    private var pushedRoute: List<GeoPoint>? = null

    private val isMainThread get() = Looper.myLooper() == Looper.getMainLooper()

    /**
     * Attaches the mirror to [surface] at [widthPx] x [heightPx] pixels.
     *
     * The caller owns [surface] (encoder input or an offscreen target) and
     * must not release it before [release]. Re-attaching tears the previous
     * renderer down first, replaying camera/puck/route on the new one like
     * `CarMapRenderer.onSurfaceAvailable`.
     */
    fun setSurface(surface: Surface, widthPx: Int, heightPx: Int) {
        if (isMainThread) attach(surface, widthPx, heightPx)
        else mainHandler.post { attach(surface, widthPx, heightPx) }
    }

    /**
     * Renders one guidance snapshot into the mirror. Safe from any thread;
     * the push marshals onto main.
     *
     * No-op until [setSurface]: a fix before the surface exists is dropped,
     * and the next fix repaints -- positions arrive at ~1 Hz, so nothing
     * worth queuing is lost. Map follows the system theme through
     * [setDark]; idle (no route) draws north-up with the puck, navigating
     * draws heading-up with the route.
     */
    fun render(snapshot: NavSnapshot) {
        if (isMainThread) push(snapshot)
        else mainHandler.post { push(snapshot) }
    }

    /** Switches the basemap palette; free (push constant, no re-tessellation). */
    fun setDark(dark: Boolean) {
        if (isMainThread) renderer?.setPalette(dark = dark, muted = false)
        else mainHandler.post { renderer?.setPalette(dark = dark, muted = false) }
    }

    /** Tears the renderer down; the caller still owns and releases the surface. */
    fun release() {
        if (isMainThread) teardown()
        else mainHandler.post { teardown() }
    }

    /** Whether the last attach produced a drawing renderer. */
    fun isRendering(): Boolean = renderer?.renderState is MapRenderState.Rendering

    private fun attach(surface: Surface, widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        teardown()
        mirrorSurface = surface
        mirrorWidth = widthPx
        mirrorHeight = heightPx
        val created = runCatching {
            SurfaceMapRenderer(appContext, density).apply {
                setPalette(dark = false, muted = false)
                setLayers(CAR_LAYERS)
                attachSurface(surface, widthPx, heightPx)
            }
        }.getOrNull() ?: run {
            Log.e(TAG, "car map mirror will not draw: renderer creation failed")
            return
        }
        val state = created.renderState
        if (state is MapRenderState.Unavailable) {
            Log.e(TAG, "car map mirror will not draw: ${state.reason}")
            created.destroy()
            return
        }
        // The route outlives a surface recreation (host rebind, backgrounding):
        // replay the tessellated mesh so the first frame already has it.
        pushedRoute?.let { created.setRoute(it) }
        created.start()
        renderer = created
    }

    private fun push(snapshot: NavSnapshot) {
        val active = renderer ?: return
        val position = GeoPoint(
            longitude = snapshot.longitudeE7 / E7,
            latitude = snapshot.latitudeE7 / E7,
        )
        active.setUserPuck(
            UserPuck(position = position, bearing = snapshot.bearingDeg),
        )
        val route = snapshot.route
        val navigating = snapshot.guidanceActive && route != null
        if (navigating && route != null) {
            val points = route.map { GeoPoint(it.longitude, it.latitude) }
            // Identity gate: the monitor forwards the provider's list
            // untouched, so an unchanged route skips the tessellation.
            if (points !== pushedRoute) {
                pushedRoute = points
                active.setRoute(points)
            }
            active.camera = com.vayunmathur.library.map.CameraPosition(
                target = position,
                zoom = NAVIGATING_ZOOM,
                bearing = snapshot.bearingDeg?.toDouble() ?: 0.0,
            )
        } else {
            if (pushedRoute != null) {
                pushedRoute = null
                active.setRoute(points = null)
            }
            active.camera = com.vayunmathur.library.map.CameraPosition(
                target = position,
                zoom = IDLE_ZOOM,
            )
        }
    }

    private fun teardown() {
        renderer?.destroy()
        renderer = null
        mirrorSurface = null
        pushedRoute = null
    }

    private companion object {
        const val TAG = "MaAuto.Maps"

        /** Same layers as the car-app host path (`CarMapRenderer.CAR_LAYERS`). */
        val CAR_LAYERS = LayerOptions(poi = true, transit = false)

        /** Matches `NavMapScreen`: navigating zooms in, idle shows context. */
        const val NAVIGATING_ZOOM = 17.0
        const val IDLE_ZOOM = 15.0

        const val E7 = 10_000_000.0
    }
}
