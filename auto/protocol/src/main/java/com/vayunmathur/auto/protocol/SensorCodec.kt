package com.vayunmathur.auto.protocol

import com.vayunmathur.auto.protocol.gal.DrivingStatusReading
import com.vayunmathur.auto.protocol.gal.LocationReading
import com.vayunmathur.auto.protocol.gal.NightReading
import com.vayunmathur.auto.protocol.gal.SensorBatch
import com.vayunmathur.auto.protocol.gal.SensorError
import com.vayunmathur.auto.protocol.gal.SensorEvent
import com.vayunmathur.auto.protocol.gal.SensorRequest
import com.vayunmathur.auto.protocol.gal.SensorResponse
import com.vayunmathur.auto.protocol.gal.SensorType
import com.vayunmathur.auto.protocol.gal.SpeedReading

/** Last-known location fix from a LOCATION batch event, or null before the first one. */
data class LocationFix(
    val latitudeE7: Long,
    val longitudeE7: Long,
    val accuracyM: Int?,
    val timestampMs: Long?,
)

/**
 * The phone-side sensor picture: last-known value per stub type.
 *
 * Stub defaults (Phase 5): parked until a DRIVING_STATUS event says otherwise,
 * standing still until a SPEED event arrives, night unknown (the owner follows
 * the phone's own night state -- see SensorChannel), location unknown until
 * the first LOCATION batch. An event that carries no payload changes nothing:
 * the owner keeps its last-known rather than clobbering it with a default.
 *
 * Maps seam (Phase 6, maps-dev): feed live batches through [withEvents] --
 * phone GPS into LOCATION, fused speed into SPEED -- and read the snapshot
 * for the car UI. Nothing here needs to change shape, only the source of
 * the events.
 */
data class SensorSnapshot(
    val parked: Boolean = true,
    val isNight: Boolean? = null,
    val location: LocationFix? = null,
    val speedMps: Double = 0.0,
) {
    /**
     * Folds one batch's events into a new snapshot. Payload-less events are
     * skipped; unknown sensor types never reach here (the codec drops them).
     */
    fun withEvents(events: List<SensorEvent>): SensorSnapshot {
        var next = this
        for (event in events) {
            next = when (event.sensorType) {
                SensorType.LOCATION -> if (event.hasLocation()) {
                    val fix = event.location
                    next.copy(
                        location = LocationFix(
                            latitudeE7 = fix.latitudeE7,
                            longitudeE7 = fix.longitudeE7,
                            accuracyM = if (fix.hasAccuracyM()) fix.accuracyM else null,
                            timestampMs = if (fix.hasTimestampMs()) fix.timestampMs else null,
                        ),
                    )
                } else {
                    next
                }
                SensorType.SPEED -> if (event.hasSpeed() && event.speed.hasSpeedMps()) {
                    next.copy(speedMps = event.speed.speedMps)
                } else {
                    next
                }
                SensorType.NIGHT_MODE -> if (event.hasNight() && event.night.hasIsNight()) {
                    next.copy(isNight = event.night.isNight)
                } else {
                    next
                }
                SensorType.DRIVING_STATUS_DATA -> if (event.hasDrivingStatus() &&
                    event.drivingStatus.hasParked()
                ) {
                    next.copy(parked = event.drivingStatus.parked)
                } else {
                    next
                }
                else -> next
            }
        }
        return next
    }
}

/** A classified inbound ch7 message. Anything else on the channel is [Observed]. */
sealed interface InboundSensor {
    /** The head unit answered a subscription (0x8002); [status] 0 means subscribed. */
    data class Subscribed(val type: SensorType, val status: Int) : InboundSensor

    /** Streamed readings (0x8003); empty when the batch carried no events. */
    data class Readings(val events: List<SensorEvent>) : InboundSensor

    /**
     * A subscription or stream failure (0x8004).
     *
     * NOTE the alias: 0x8004 is MediaAck on media channels and the messaging
     * ACTION on ch14, so this only ever means SensorError on ch7. Owners must
     * scope by channel id -- the 0x04 CONTROL frame flag never enters routing.
     */
    data class Error(val type: SensorType, val status: Int) : InboundSensor

    /** Malformed bytes, a future extension, anything unclassified. Observed, never fatal. */
    data class Observed(val type: Int) : InboundSensor
}

/**
 * Pure sensor-channel (SENSOR_SOURCE, service 7) codecs.
 *
 * Android-free like everything else in this module, so subscribing, batch
 * decoding and the last-known fold are all host-testable. The car owns the
 * sensors: the phone SENDS [GalMessage.Sensor.SENSOR_REQUEST] to subscribe
 * and RECEIVES [GalMessage.Sensor.SENSOR_RESPONSE], [GalMessage.Sensor.SENSOR_BATCH]
 * and [GalMessage.Sensor.SENSOR_ERROR]. Sends go out with
 * `GalConnection.send(channelId, type, payload)` -- always encrypted, never
 * CONTROL-flagged, like all service-channel traffic.
 *
 * Direction ground truth (auto/docs/FINDINGS.md section 2, the `rvb`
 * endpoint): `xnu` request fields are (1 sensor_type, 2 min_update_period).
 * Every other field number here is MA sender-defined -- the teardown never
 * recovered the `xnv`/`xnr`/`xns` layouts -- so exact-bytes tests pin OUR
 * numbering to catch an accidental renumber here, not at a DHU.
 */
object SensorCodec {

    /**
     * The stub subscription set: the only types the stub subscribes to on the
     * ch7 grant. Parked, night, last-known location, speed 0 -- everything
     * maps-dev needs for the Phase 6 seam, nothing the DHU must stream for
     * the 5-minute ACTIVE verdict.
     */
    val STUB_SUBSCRIPTIONS: List<SensorType> = listOf(
        SensorType.LOCATION,
        SensorType.NIGHT_MODE,
        SensorType.SPEED,
        SensorType.DRIVING_STATUS_DATA,
    )

    /**
     * Phone -> HU: subscribe to [type]. The period is omitted for the head
     * unit's default rate -- only pass one to slow a chatty sensor down.
     */
    fun encodeSubscribe(type: SensorType, minPeriodMs: Long? = null): Pair<Int, ByteArray> {
        val builder = SensorRequest.newBuilder().setSensorType(type)
        if (minPeriodMs != null) builder.minUpdatePeriodMs = minPeriodMs
        return GalMessage.Sensor.SENSOR_REQUEST to builder.build().toByteArray()
    }

    /** Phone -> HU: unsubscribe (a request with period -1). */
    fun encodeUnsubscribe(type: SensorType): Pair<Int, ByteArray> =
        encodeSubscribe(type, GalMessage.Sensor.UNSUBSCRIBE_PERIOD)

    /**
     * Parses a HU -> phone subscription answer, throwing on malformed bytes
     * like every other generated parser here. Callers that must survive
     * hostile traffic use [decodeInbound] instead.
     */
    fun decodeResponse(payload: ByteArray): SensorResponse = SensorResponse.parseFrom(payload)

    /**
     * Classifies one inbound ch7 message, never throwing: malformed bytes,
     * uninitialized payloads (a required field dropped, e.g. an unknown enum
     * the lite runtime refused) and unknown types all decode to
     * [InboundSensor.Observed] -- the pump logs them and carries on.
     *
     * Route by channel id before calling: 0x8004 is aliased across services
     * (MediaAck on media channels, ACTION on ch14), so only ch7 traffic
     * belongs here.
     */
    fun decodeInbound(type: Int, payload: ByteArray): InboundSensor = when (type) {
        GalMessage.Sensor.SENSOR_RESPONSE -> runCatching { SensorResponse.parseFrom(payload) }
            .getOrNull()
            ?.takeIf { it.isInitialized }
            ?.let { InboundSensor.Subscribed(it.sensorType, if (it.hasStatus()) it.status else 0) }
            ?: InboundSensor.Observed(type)
        GalMessage.Sensor.SENSOR_BATCH -> runCatching { SensorBatch.parseFrom(payload) }
            .getOrNull()
            ?.takeIf { it.isInitialized }
            ?.let { InboundSensor.Readings(it.eventsList) }
            ?: InboundSensor.Observed(type)
        GalMessage.Sensor.SENSOR_ERROR -> runCatching { SensorError.parseFrom(payload) }
            .getOrNull()
            ?.takeIf { it.isInitialized }
            ?.let { InboundSensor.Error(it.sensorType, if (it.hasStatus()) it.status else 0) }
            ?: InboundSensor.Observed(type)
        else -> InboundSensor.Observed(type)
    }

    /** Builds one batch carrying location, for tests and head-unit stand-ins. */
    fun buildLocationBatch(
        latitudeE7: Long,
        longitudeE7: Long,
        accuracyM: Int? = null,
        timestampMs: Long? = null,
    ): ByteArray {
        val fix = LocationReading.newBuilder()
            .setLatitudeE7(latitudeE7)
            .setLongitudeE7(longitudeE7)
        if (accuracyM != null) fix.accuracyM = accuracyM
        if (timestampMs != null) fix.timestampMs = timestampMs
        return SensorBatch.newBuilder()
            .addEvents(
                SensorEvent.newBuilder()
                    .setSensorType(SensorType.LOCATION)
                    .setLocation(fix),
            )
            .build()
            .toByteArray()
    }

    /** Builds one batch carrying speed, for tests and head-unit stand-ins. */
    fun buildSpeedBatch(speedMps: Double): ByteArray = SensorBatch.newBuilder()
        .addEvents(
            SensorEvent.newBuilder()
                .setSensorType(SensorType.SPEED)
                .setSpeed(SpeedReading.newBuilder().setSpeedMps(speedMps)),
        )
        .build()
        .toByteArray()

    /** Builds one batch carrying night mode, for tests and head-unit stand-ins. */
    fun buildNightBatch(isNight: Boolean): ByteArray = SensorBatch.newBuilder()
        .addEvents(
            SensorEvent.newBuilder()
                .setSensorType(SensorType.NIGHT_MODE)
                .setNight(NightReading.newBuilder().setIsNight(isNight)),
        )
        .build()
        .toByteArray()

    /** Builds one batch carrying parked state, for tests and head-unit stand-ins. */
    fun buildDrivingStatusBatch(parked: Boolean): ByteArray = SensorBatch.newBuilder()
        .addEvents(
            SensorEvent.newBuilder()
                .setSensorType(SensorType.DRIVING_STATUS_DATA)
                .setDrivingStatus(DrivingStatusReading.newBuilder().setParked(parked)),
        )
        .build()
        .toByteArray()
}
