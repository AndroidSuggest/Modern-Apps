package com.vayunmathur.auto.platform

import android.util.Log
import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalService
import com.vayunmathur.auto.protocol.NavStatusCodec
import com.vayunmathur.auto.protocol.NavStatusUpdate

/**
 * The navigation-status channel: posts phone-side turn guidance onto ch10.
 *
 * The channel is phone -> head unit simplex: the phone posts, the head unit
 * sends nothing normative back, so inbound ch10 traffic is observed and
 * ignored. ch10 opens in the generic wire-order pass; the stub post goes out
 * on the grant -- sending on a channel the head unit has not opened earns a
 * bare MessageError (0xff).
 *
 * Stub phase (Phase 5): one well-formed inactive post ("no guidance",
 * stated explicitly rather than implied), then quiet -- the well-formed
 * nack the spec calls for. Live turn updates land with Phase 6 maps-dev
 * through [postStatus]: next road, distance metres and maneuver token per
 * turn, reposted as the route advances.
 */
class NavStatusChannel(
    private val connection: GalConnection,
    private val onEvent: (SensorEvent) -> Unit = {},
) {
    val channelId: Int get() = GalService.NAVIGATION_STATUS.id

    private var open = false

    /**
     * The last update that went out, seeded with the grant-time inactive
     * post. [postUpdate] skips reposts of identical state so a stationary
     * fix rate does not spam the cluster with the same turn.
     */
    private var lastPosted: NavStatusUpdate? = NavStatusUpdate(guidanceActive = false)

    /** The grant arrived: post the inactive stub. Called once per channel open. */
    fun onChannelOpen() {
        open = true
        lastPosted = NavStatusUpdate(guidanceActive = false)
        val (type, payload) = NavStatusCodec.encodeInactive()
        connection.send(channelId, type, payload)
        Log.i(TAG, "nav-status posted: no guidance")
        onEvent(SensorEvent.NavStatusPosted)
    }

    /** One message for this channel; the head unit sends nothing normative, so all inbound is observed. */
    fun onMessage(channelId: Int, type: Int, payload: ByteArray) {
        if (channelId != this.channelId) {
            Log.w(TAG, "ignoring 0x${type.toString(16)} for channel $channelId")
            return
        }
        Log.d(TAG, "unhandled nav-status message 0x${type.toString(16)}")
    }

    /**
     * Live-route seam (HANDOFF.md section 10, maps-dev): forwards one
     * guidance snapshot -- `MapsGuidance.toNavStatus(snapshot)` -- as a turn
     * update while the route advances. Identical reposts are skipped (see
     * [lastPosted]); the grant-time inactive stub stays the no-guidance
     * state, and posts before the grant are dropped, never queued.
     */
    fun postUpdate(update: NavStatusUpdate) {
        if (!open) return
        if (update == lastPosted) return
        lastPosted = update
        postStatus(update.guidanceActive, update.nextRoad, update.nextTurnDistanceM, update.maneuver)
    }

    /**
     * Live-guidance seam (Phase 6, maps-dev): post a turn update. Posts
     * before the grant are dropped, never queued -- the grant post carries
     * the state and the next live post overwrites it.
     */
    fun postStatus(
        guidanceActive: Boolean,
        nextRoad: String? = null,
        nextTurnDistanceM: Int? = null,
        maneuver: String? = null,
    ) {
        if (!open) return
        val (type, payload) = NavStatusCodec.encodeStatus(
            guidanceActive,
            nextRoad,
            nextTurnDistanceM,
            maneuver,
        )
        connection.send(channelId, type, payload)
        onEvent(SensorEvent.NavStatusPosted)
    }

    fun release() {
        open = false
    }

    private companion object {
        const val TAG = "MaAuto.NavStatus"
    }
}
