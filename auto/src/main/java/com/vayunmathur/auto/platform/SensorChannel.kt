package com.vayunmathur.auto.platform

import android.util.Log
import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalService
import com.vayunmathur.auto.protocol.InboundSensor
import com.vayunmathur.auto.protocol.SensorCodec
import com.vayunmathur.auto.protocol.SensorSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the stub answers "is it night": the head unit's NIGHT_MODE stream wins
 * when it has spoken, otherwise the phone's own night state decides.
 *
 * Maps seam (Phase 6, maps-dev): replace the lambda with the Maps/theme
 * source and feed live batches through [SensorChannel.onValues] -- the
 * channel shape stays the same, only the sources change.
 */
fun interface NightSource {
    /** The phone's own night state, consulted until the HU reports NIGHT_MODE. */
    fun phoneNight(): Boolean
}

/**
 * The sensor source channel: subscribes to the stub set on the ch7 grant and
 * folds the head unit's batches into a last-known snapshot.
 *
 * Bring-up needs nothing beyond the generic open: ch7 opens in the wire-order
 * pass like every other service, and subscribes go out on the grant --
 * sending on a channel the head unit has not opened earns a bare MessageError
 * (0xff). One channel at a time is enforced by the pump's sequential open
 * order; subscriptions batch behind a single flush the same way.
 *
 * Route by channel id: 0x8004 is SensorError here but MediaAck on media
 * channels and ACTION on ch14, so only ch7 traffic belongs to [SensorCodec].
 * The 0x04 CONTROL frame flag does NOT mean "control channel" (HANDOFF.md
 * section 9) and never enters this decision.
 *
 * Full live values (Phase 6, maps-dev): feed phone GPS / fused speed through
 * [onValues] -- see [SensorSnapshot.withEvents] -- and read [snapshot] for
 * the car UI. The stub posts nothing upstream; it only subscribes.
 */
class SensorChannel(
    private val connection: GalConnection,
    private val night: NightSource = NightSource { false },
    private val onEvent: (SensorEvent) -> Unit = {},
    /** Live-value seam: batches from phone sensors (maps-dev) fold in like HU batches. */
    private val onValues: (SensorSnapshot) -> Unit = {},
) {
    val channelId: Int get() = GalService.SENSOR_SOURCE.id

    private val _snapshot = MutableStateFlow(SensorSnapshot())
    /** Last-known sensor values; parked / speed 0 / night-unknown until batches move them. */
    val snapshot: StateFlow<SensorSnapshot> = _snapshot.asStateFlow()

    private var open = false

    /**
     * The grant arrived: subscribe to the stub set. Called once per channel
     * open. Night follows the phone until the first NIGHT_MODE batch -- the
     * snapshot seeds from [night] now so the car UI has an answer before the
     * head unit streams anything.
     */
    fun onChannelOpen() {
        open = true
        val seeded = _snapshot.value.copy(isNight = night.phoneNight())
        _snapshot.value = seeded
        for (type in SensorCodec.STUB_SUBSCRIPTIONS) {
            val (requestType, payload) = SensorCodec.encodeSubscribe(type)
            connection.send(channelId, requestType, payload)
            Log.i(TAG, "sensor subscribe: $type")
            onEvent(SensorEvent.Subscribed(type.name))
        }
    }

    /** One message for this channel; anything else is ignored, never misparsed. */
    fun onMessage(channelId: Int, type: Int, payload: ByteArray) {
        if (channelId != this.channelId) {
            Log.w(TAG, "ignoring 0x${type.toString(16)} for channel $channelId")
            return
        }
        when (val inbound = SensorCodec.decodeInbound(type, payload)) {
            is InboundSensor.Subscribed -> {
                Log.i(TAG, "sensor subscribed: ${inbound.type} status=${inbound.status}")
                onEvent(SensorEvent.SubscriptionAnswered(inbound.type.name, inbound.status))
            }
            is InboundSensor.Readings -> {
                // A NIGHT_MODE event with a payload overrides the phone seed;
                // payload-less events keep last-known inside withEvents.
                val next = _snapshot.value.withEvents(inbound.events)
                _snapshot.value = next
                onValues(next)
                Log.d(TAG, "sensor batch: ${inbound.events.size} events")
                onEvent(SensorEvent.BatchReceived(inbound.events.size))
            }
            is InboundSensor.Error -> {
                // Observed, never fatal: the bring-up and video carry on.
                Log.w(TAG, "sensor error: ${inbound.type} status=${inbound.status}")
                onEvent(SensorEvent.SensorError(inbound.type.name, inbound.status))
            }
            is InboundSensor.Observed -> Log.d(TAG, "unhandled sensor message 0x${type.toString(16)}")
        }
    }

    /** Unsubscribes the stub set; the pump calls this on teardown before release. */
    fun release() {
        if (!open) return
        open = false
        // Best-effort: the socket may already be going away in teardown, and
        // an unsubscribe is courtesy, not protocol -- never fail the parting.
        for (type in SensorCodec.STUB_SUBSCRIPTIONS) {
            runCatching {
                val (requestType, payload) = SensorCodec.encodeUnsubscribe(type)
                connection.send(channelId, requestType, payload)
            }
        }
    }

    private companion object {
        const val TAG = "MaAuto.Sensors"
    }
}
