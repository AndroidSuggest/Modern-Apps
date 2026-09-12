package com.vayunmathur.auto.protocol

import com.vayunmathur.auto.protocol.gal.NavigationStatus

/**
 * Pure navigation-status channel (service 10) codecs.
 *
 * Android-free like everything else in this module, so the status encoding
 * is host-testable. The channel is phone -> head unit simplex: the phone
 * posts turn guidance for the cluster and display, and the head unit sends
 * nothing normative back -- inbound ch10 traffic is observed and ignored.
 *
 * MA sender-defined throughout: the teardown recovered the service
 * descriptor slot but never the channel message IDs, so STATUS (0x8001)
 * mirrors the aasdk/openauto phone-to-HU status. A head unit that does not
 * speak it answers with a bare MessageError (0xff) on ch10, which is
 * observed and non-fatal. Sends go out with
 * `GalConnection.send(channelId, type, payload)` -- always encrypted, never
 * CONTROL-flagged, like all service-channel traffic.
 *
 * Stub phase (Phase 5): one well-formed inactive post on the grant, then
 * quiet. Live turn updates land with Phase 6 maps-dev through [encodeStatus].
 */
object NavStatusCodec {

    /**
     * Phone -> HU: "no guidance". States the inactive state explicitly
     * (`guidance_active` false, 08 00 on the wire) rather than posting an
     * empty payload the head unit must interpret.
     */
    fun encodeInactive(): Pair<Int, ByteArray> =
        GalMessage.NavigationStatus.STATUS to NavigationStatus.newBuilder()
            .setGuidanceActive(false)
            .build()
            .toByteArray()

    /**
     * Phone -> HU: live turn guidance. Absent fields stay absent -- a post
     * with only [guidanceActive] set is a bare active/inactive flip, which is
     * all the stub ever needs beyond [encodeInactive].
     *
     * Maps seam (Phase 6, maps-dev): next road, distance metres and maneuver
     * token per turn, reposted as the route advances.
     */
    fun encodeStatus(
        guidanceActive: Boolean,
        nextRoad: String? = null,
        nextTurnDistanceM: Int? = null,
        maneuver: String? = null,
    ): Pair<Int, ByteArray> {
        val builder = NavigationStatus.newBuilder().setGuidanceActive(guidanceActive)
        if (nextRoad != null) builder.nextRoad = nextRoad
        if (nextTurnDistanceM != null) builder.nextTurnDistanceM = nextTurnDistanceM
        if (maneuver != null) builder.maneuver = maneuver
        return GalMessage.NavigationStatus.STATUS to builder.build().toByteArray()
    }
}
