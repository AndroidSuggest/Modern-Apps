package com.vayunmathur.auto.protocol

import com.vayunmathur.auto.protocol.gal.AccelerometerReading
import com.vayunmathur.auto.protocol.gal.CompassReading
import com.vayunmathur.auto.protocol.gal.DeadReckoningReading
import com.vayunmathur.auto.protocol.gal.DoorReading
import com.vayunmathur.auto.protocol.gal.DrivingStatusReading
import com.vayunmathur.auto.protocol.gal.EnvironmentReading
import com.vayunmathur.auto.protocol.gal.FuelReading
import com.vayunmathur.auto.protocol.gal.GearReading
import com.vayunmathur.auto.protocol.gal.GpsSatelliteReading
import com.vayunmathur.auto.protocol.gal.GyroscopeReading
import com.vayunmathur.auto.protocol.gal.HvacReading
import com.vayunmathur.auto.protocol.gal.LightReading
import com.vayunmathur.auto.protocol.gal.LocationReading
import com.vayunmathur.auto.protocol.gal.NightReading
import com.vayunmathur.auto.protocol.gal.ObdiiReading
import com.vayunmathur.auto.protocol.gal.OdometerReading
import com.vayunmathur.auto.protocol.gal.ParkingBrakeReading
import com.vayunmathur.auto.protocol.gal.PassengerReading
import com.vayunmathur.auto.protocol.gal.RawEvTripReading
import com.vayunmathur.auto.protocol.gal.RawVehicleEnergyReading
import com.vayunmathur.auto.protocol.gal.RpmReading
import com.vayunmathur.auto.protocol.gal.SensorBatch
import com.vayunmathur.auto.protocol.gal.SensorError
import com.vayunmathur.auto.protocol.gal.SensorEvent
import com.vayunmathur.auto.protocol.gal.SensorRequest
import com.vayunmathur.auto.protocol.gal.SensorResponse
import com.vayunmathur.auto.protocol.gal.SensorType
import com.vayunmathur.auto.protocol.gal.SpeedReading
import com.vayunmathur.auto.protocol.gal.TirePressureReading
import com.vayunmathur.auto.protocol.gal.TollCardReading

/** Last-known location fix from a LOCATION batch event, or null before the first one. */
data class LocationFix(
    val latitudeE7: Long,
    val longitudeE7: Long,
    val accuracyM: Int?,
    val timestampMs: Long?,
)

/**
 * The phone-side sensor picture: last-known value per type.
 *
 * Stub defaults: parked until a DRIVING_STATUS event says otherwise,
 * standing still until a SPEED event arrives, night unknown (the owner follows
 * the phone's own night state -- see SensorChannel), location unknown until
 * the first LOCATION batch, every other reading unknown until its first
 * batch. An event that carries no payload changes nothing: the owner keeps
 * its last-known rather than clobbering it with a default.
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
    val compassHeadingDeg: Int? = null,
    val rpm: RpmReading? = null,
    val odometer: OdometerReading? = null,
    val fuel: FuelReading? = null,
    val parkingBrake: ParkingBrakeReading? = null,
    val gear: GearReading? = null,
    val obdii: ObdiiReading? = null,
    val environment: EnvironmentReading? = null,
    val hvac: HvacReading? = null,
    val deadReckoning: DeadReckoningReading? = null,
    val passenger: PassengerReading? = null,
    val door: DoorReading? = null,
    val light: LightReading? = null,
    val tirePressure: TirePressureReading? = null,
    val accelerometer: AccelerometerReading? = null,
    val gyroscope: GyroscopeReading? = null,
    val gpsSatellite: GpsSatelliteReading? = null,
    val tollCard: TollCardReading? = null,
    val rawVehicleEnergy: RawVehicleEnergyReading? = null,
    val rawEvTrip: RawEvTripReading? = null,
) {
    /**
     * Folds one batch's events into a new snapshot. Payload-less events are
     * skipped; VEHICLE_ENERGY_MODEL_DATA and TRAILER_DATA carry no payload
     * (MISSING -- see `gal/sensors.proto`) and likewise change nothing;
     * unknown sensor types never reach here (the codec drops them).
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
                SensorType.COMPASS -> if (event.hasCompass() && event.compass.hasHeadingDeg()) {
                    next.copy(compassHeadingDeg = event.compass.headingDeg)
                } else {
                    next
                }
                SensorType.RPM -> if (event.hasRpm()) next.copy(rpm = event.rpm) else next
                SensorType.ODOMETER -> if (event.hasOdometer()) {
                    next.copy(odometer = event.odometer)
                } else {
                    next
                }
                SensorType.FUEL -> if (event.hasFuel()) next.copy(fuel = event.fuel) else next
                SensorType.PARKING_BRAKE -> if (event.hasParkingBrake()) {
                    next.copy(parkingBrake = event.parkingBrake)
                } else {
                    next
                }
                SensorType.GEAR -> if (event.hasGear()) next.copy(gear = event.gear) else next
                SensorType.OBDII_DIAGNOSTIC_CODE -> if (event.hasObdii()) {
                    next.copy(obdii = event.obdii)
                } else {
                    next
                }
                SensorType.ENVIRONMENT_DATA -> if (event.hasEnvironment()) {
                    next.copy(environment = event.environment)
                } else {
                    next
                }
                SensorType.HVAC_DATA -> if (event.hasHvac()) next.copy(hvac = event.hvac) else next
                SensorType.DEAD_RECKONING_DATA -> if (event.hasDeadReckoning()) {
                    next.copy(deadReckoning = event.deadReckoning)
                } else {
                    next
                }
                SensorType.PASSENGER_DATA -> if (event.hasPassenger()) {
                    next.copy(passenger = event.passenger)
                } else {
                    next
                }
                SensorType.DOOR_DATA -> if (event.hasDoor()) next.copy(door = event.door) else next
                SensorType.LIGHT_DATA -> if (event.hasLight()) {
                    next.copy(light = event.light)
                } else {
                    next
                }
                SensorType.TIRE_PRESSURE_DATA -> if (event.hasTirePressure()) {
                    next.copy(tirePressure = event.tirePressure)
                } else {
                    next
                }
                SensorType.ACCELEROMETER_DATA -> if (event.hasAccelerometer()) {
                    next.copy(accelerometer = event.accelerometer)
                } else {
                    next
                }
                SensorType.GYROSCOPE_DATA -> if (event.hasGyroscope()) {
                    next.copy(gyroscope = event.gyroscope)
                } else {
                    next
                }
                SensorType.GPS_SATELLITE_DATA -> if (event.hasGpsSatellite()) {
                    next.copy(gpsSatellite = event.gpsSatellite)
                } else {
                    next
                }
                SensorType.TOLL_CARD -> if (event.hasTollCard()) {
                    next.copy(tollCard = event.tollCard)
                } else {
                    next
                }
                SensorType.RAW_VEHICLE_ENERGY_MODEL -> if (event.hasRawVehicleEnergy()) {
                    next.copy(rawVehicleEnergy = event.rawVehicleEnergy)
                } else {
                    next
                }
                SensorType.RAW_EV_TRIP_SETTINGS -> if (event.hasRawEvTrip()) {
                    next.copy(rawEvTrip = event.rawEvTrip)
                } else {
                    next
                }
                // MISSING payloads (`gal/sensors.proto`): `xgu` unrecovered,
                // `xon` empty -- nothing to fold, last-known stands.
                SensorType.VEHICLE_ENERGY_MODEL_DATA,
                SensorType.TRAILER_DATA -> next
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
     * How long a subscribe (0x8001) may go unanswered before it is a timeout.
     * The `rvb` endpoint is synchronous with a 2000 ms timeout (documented
     * `GalMessage.Sensor`, `gal/sensors.proto`); enforced by
     * [SensorRequestTracker], which the ch7 owner drives.
     */
    const val REQUEST_TIMEOUT_MS = 2_000L

    /**
     * Status for a locally synthesized timeout error. Never a head-unit
     * status on the wire: timeout errors are built phone-side by
     * [SensorRequestTracker.takeTimedOut], never parsed, so this marker
     * cannot collide with anything the head unit sends.
     */
    const val TIMEOUT_STATUS = -1

    /**
     * The subscription set: all 26 `xny` types in dumper order. The name is
     * kept for the ch7 owner, which iterates it for subscribes and
     * unsubscribes; it no longer means "stub-only". The head unit answers
     * each subscribe (or errors per type, observed and non-fatal), and
     * unanswered subscribes surface through [SensorRequestTracker] after
     * [REQUEST_TIMEOUT_MS].
     */
    val STUB_SUBSCRIPTIONS: List<SensorType> = listOf(
        SensorType.LOCATION,
        SensorType.COMPASS,
        SensorType.SPEED,
        SensorType.RPM,
        SensorType.ODOMETER,
        SensorType.FUEL,
        SensorType.PARKING_BRAKE,
        SensorType.GEAR,
        SensorType.OBDII_DIAGNOSTIC_CODE,
        SensorType.NIGHT_MODE,
        SensorType.ENVIRONMENT_DATA,
        SensorType.HVAC_DATA,
        SensorType.DRIVING_STATUS_DATA,
        SensorType.DEAD_RECKONING_DATA,
        SensorType.PASSENGER_DATA,
        SensorType.DOOR_DATA,
        SensorType.LIGHT_DATA,
        SensorType.TIRE_PRESSURE_DATA,
        SensorType.ACCELEROMETER_DATA,
        SensorType.GYROSCOPE_DATA,
        SensorType.GPS_SATELLITE_DATA,
        SensorType.TOLL_CARD,
        SensorType.VEHICLE_ENERGY_MODEL_DATA,
        SensorType.TRAILER_DATA,
        SensorType.RAW_VEHICLE_ENERGY_MODEL,
        SensorType.RAW_EV_TRIP_SETTINGS,
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

    /** Builds one batch carrying compass heading, for tests and head-unit stand-ins. */
    fun buildCompassBatch(headingDeg: Int): ByteArray = SensorBatch.newBuilder()
        .addEvents(
            SensorEvent.newBuilder()
                .setSensorType(SensorType.COMPASS)
                .setCompass(
                    CompassReading.newBuilder()
                        .setHeadingDeg(headingDeg),
                ),
        )
        .build()
        .toByteArray()
}

/**
 * Tracks ch7 subscribes against the 2 s `rvb` timeout.
 *
 * The owner marks each subscribe it sends and each subscription answer it
 * receives; [takeTimedOut] reports the subscribes that went unanswered as
 * synthesized [InboundSensor.Error]s (status [SensorCodec.TIMEOUT_STATUS]),
 * each exactly once. Timestamps are explicit millisecond values (not a
 * clock) so the whole thing stays Android-free and host-testable.
 *
 * Wiring note: the ch7 owner does not drive this yet -- subscribes go out
 * fire-and-forget from `SensorChannel.onChannelOpen`. When it is wired, mark
 * answers in the `Subscribed` branch and sweep timeouts on a 2 s tick or
 * before each new subscribe round.
 */
class SensorRequestTracker {
    private val requestedAt = mutableMapOf<SensorType, Long>()

    /** Records a subscribe sent at [nowMs]. Re-sending restarts the timeout. */
    fun markRequested(type: SensorType, nowMs: Long) {
        requestedAt[type] = nowMs
    }

    /** Records the subscription answer: no timeout can fire for [type] now. */
    fun markAnswered(type: SensorType) {
        requestedAt.remove(type)
    }

    /**
     * Subscribes older than [timeoutMs] with no answer, as errors. Each is
     * reported once: taking them clears them, so a still-silent head unit
     * must be re-marked (re-subscribed) to time out again.
     */
    fun takeTimedOut(
        nowMs: Long,
        timeoutMs: Long = SensorCodec.REQUEST_TIMEOUT_MS,
    ): List<InboundSensor.Error> {
        val timedOut = requestedAt
            .filter { nowMs - it.value >= timeoutMs }
            .keys
            .toList()
        timedOut.forEach { requestedAt.remove(it) }
        return timedOut.map { InboundSensor.Error(it, SensorCodec.TIMEOUT_STATUS) }
    }
}
