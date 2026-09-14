package com.vayunmathur.auto.service

import android.content.Context
import com.vayunmathur.auto.platform.CarAppHost
import com.vayunmathur.auto.platform.VideoSinkChannel

/**
 * Session-scoped owner for the car-app host (`CarAppHost`).
 *
 * Split out of `ProjectionService` for the 800-line limit: the service owns
 * session lifecycle, this owns the Maps bind plus every video-sink hookup the
 * host needs (template states, map surface, night, map touches). Session
 * thread owns start/stop; the video sink is touched from `openNext` on the
 * pump thread like every other `sink.set*` call.
 */
class CarAppHostSession(
    private val context: Context,
    private val video: () -> VideoSinkChannel?,
) {
    private var host: CarAppHost? = null

    /** Binds `MapsCarAppService`; template states push into the car card. */
    fun start() {
        if (host != null) return
        host = CarAppHost(context) { state ->
            video()?.setHostNavState(state)
        }.also { it.bind() }
    }

    /**
     * Wires the host into a video sink: night as a configuration change,
     * the nav-card surface as the app's `SurfaceContainer`, and map touches
     * into the app's own `SurfaceCallback`. Call from `openNext` with the
     * sink under construction, like every other `sink.set*` call.
     */
    fun wireInto(
        sink: VideoSinkChannel,
        snapshots: () -> com.vayunmathur.auto.protocol.NavSnapshot?,
    ) {
        val sessionHost = host ?: return
        // Night reaches the hosted map through the same call that restyles
        // the rail/cards/drawer (see setNightDark): the host forwards it as
        // a configuration change so the app restyles its own palette.
        sink.setMapDarkApplier { dark -> sessionHost.setNight(dark) }
        sink.setNavSource(
            get = snapshots,
            onMapSurface = { surface, w, h ->
                if (surface != null) sessionHost.setSurface(surface, w, h)
                else sessionHost.clearSurface()
            },
        )
        // Map-surface touches ride the hosted app's own SurfaceCallback
        // (pan/zoom/click in Maps itself).
        sink.setMapTouchForwarder { action, x, y ->
            sessionHost.injectMapTouch(action, x, y)
        }
    }

    /** Tears the session down; the service still owns the `Surface`. */
    fun stop() {
        host?.unbind()
        host = null
    }
}
