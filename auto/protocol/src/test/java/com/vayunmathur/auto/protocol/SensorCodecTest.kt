package com.vayunmathur.auto.protocol

import com.vayunmathur.auto.protocol.gal.SensorEvent
import com.vayunmathur.auto.protocol.gal.SensorRequest
import com.vayunmathur.auto.protocol.gal.SensorResponse
import com.vayunmathur.auto.protocol.gal.SensorType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the sensor-channel (service 7) wire format and the codec around it.
 *
 * Exact bytes only where the teardown is exact (the `xnu` subscribe with
 * fields 1-2, and the `xny` enum order); everywhere else round-trips pin OUR
 * field numbering so an accidental renumber fails here, not at a DHU that
 * silently ignores us.
 */
class SensorCodecTest {

    @Test
    fun `subscribe pins sensor type in field 1 and omits the period`() {
        // `xnu`: field 1 sensor_type, field 2 min_update_period. A bare
        // LOCATION subscribe is 08 01.
        val (type, payload) = SensorCodec.encodeSubscribe(SensorType.LOCATION)
        assertEquals(GalMessage.Sensor.SENSOR_REQUEST, type)
        assertContentEquals(byteArrayOf(0x08, 0x01), payload)
        val parsed = SensorRequest.parseFrom(payload)
        assertEquals(SensorType.LOCATION, parsed.sensorType)
        assertTrue(!parsed.hasMinUpdatePeriodMs())
    }

    @Test
    fun `subscribe carries an explicit period in field 2`() {
        val (_, payload) = SensorCodec.encodeSubscribe(SensorType.SPEED, minPeriodMs = 1_000)
        val parsed = SensorRequest.parseFrom(payload)
        assertEquals(SensorType.SPEED, parsed.sensorType)
        assertEquals(1_000L, parsed.minUpdatePeriodMs)
    }

    @Test
    fun `unsubscribe is a request with period minus one`() {
        val (type, payload) = SensorCodec.encodeUnsubscribe(SensorType.COMPASS)
        assertEquals(GalMessage.Sensor.SENSOR_REQUEST, type)
        assertEquals(
            GalMessage.Sensor.UNSUBSCRIBE_PERIOD,
            SensorRequest.parseFrom(payload).minUpdatePeriodMs,
        )
    }

    @Test
    fun `sensor type numbering matches the teardown order`() {
        // `xny` in `rvb.j()` dumper order; off-by-one here subscribes the
        // wrong sensor on a head unit that speaks the real layout.
        assertEquals(1, SensorType.LOCATION.number)
        assertEquals(2, SensorType.COMPASS.number)
        assertEquals(3, SensorType.SPEED.number)
        assertEquals(10, SensorType.NIGHT_MODE.number)
        assertEquals(13, SensorType.DRIVING_STATUS_DATA.number)
        assertEquals(26, SensorType.RAW_EV_TRIP_SETTINGS.number)
    }

    @Test
    fun `the stub subscribes to exactly the stub set`() {
        // All 26 `xny` types in dumper order (kept name: the ch7 owner
        // iterates this for subscribes and unsubscribes).
        assertEquals(
            listOf(
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
            ),
            SensorCodec.STUB_SUBSCRIPTIONS,
        )
    }

    @Test
    fun `a subscription answer classifies with its status`() {
        val payload = SensorResponse.newBuilder()
            .setSensorType(SensorType.SPEED)
            .setStatus(0)
            .build()
            .toByteArray()
        assertEquals(
            InboundSensor.Subscribed(SensorType.SPEED, 0),
            SensorCodec.decodeInbound(GalMessage.Sensor.SENSOR_RESPONSE, payload),
        )
    }

    @Test
    fun `a location batch folds into last-known`() {
        val batch = SensorCodec.buildLocationBatch(
            latitudeE7 = 377_699_060,
            longitudeE7 = -1_224_195_609,
            accuracyM = 8,
            timestampMs = 42L,
        )
        val inbound = SensorCodec.decodeInbound(GalMessage.Sensor.SENSOR_BATCH, batch)
        val events = (inbound as? InboundSensor.Readings)?.events
            ?: error("expected readings")
        val snapshot = SensorSnapshot().withEvents(events)
        assertEquals(377_699_060L, snapshot.location?.latitudeE7)
        assertEquals(-1_224_195_609L, snapshot.location?.longitudeE7)
        assertEquals(8, snapshot.location?.accuracyM)
        assertEquals(42L, snapshot.location?.timestampMs)
        // The stub defaults survive a location-only batch.
        assertTrue(snapshot.parked)
        assertEquals(0.0, snapshot.speedMps)
        assertNull(snapshot.isNight)
    }

    @Test
    fun `speed night and parked batches each move only their value`() {
        val start = SensorSnapshot()
        val speeded = start.withEvents(
            (SensorCodec.decodeInbound(
                GalMessage.Sensor.SENSOR_BATCH,
                SensorCodec.buildSpeedBatch(13.9),
            ) as InboundSensor.Readings).events,
        )
        assertEquals(13.9, speeded.speedMps, 1e-9)
        assertTrue(speeded.parked)

        val nighted = speeded.withEvents(
            (SensorCodec.decodeInbound(
                GalMessage.Sensor.SENSOR_BATCH,
                SensorCodec.buildNightBatch(true),
            ) as InboundSensor.Readings).events,
        )
        assertEquals(true, nighted.isNight)
        assertEquals(13.9, nighted.speedMps, 1e-9)

        val moved = nighted.withEvents(
            (SensorCodec.decodeInbound(
                GalMessage.Sensor.SENSOR_BATCH,
                SensorCodec.buildDrivingStatusBatch(parked = false),
            ) as InboundSensor.Readings).events,
        )
        assertEquals(false, moved.parked)
        assertEquals(true, moved.isNight)
    }

    @Test
    fun `a payload-less event keeps last-known instead of clobbering`() {
        val seeded = SensorSnapshot(speedMps = 5.0)
        val empty = SensorEvent.newBuilder()
            .setSensorType(SensorType.SPEED)
            .build()
        assertEquals(5.0, seeded.withEvents(listOf(empty)).speedMps, 1e-9)
    }

    @Test
    fun `a sensor error classifies with its type`() {
        val payload = com.vayunmathur.auto.protocol.gal.SensorError.newBuilder()
            .setSensorType(SensorType.LOCATION)
            .setStatus(-9)
            .build()
            .toByteArray()
        assertEquals(
            InboundSensor.Error(SensorType.LOCATION, -9),
            SensorCodec.decodeInbound(GalMessage.Sensor.SENSOR_ERROR, payload),
        )
    }

    @Test
    fun `0x8004 on ch7 is an error, never a media ack`() {
        // The alias guard: the same wire value parses as SensorError here --
        // owners that skip the channel-id check misparse this as a MediaAck.
        val error = com.vayunmathur.auto.protocol.gal.SensorError.newBuilder()
            .setSensorType(SensorType.SPEED)
            .build()
            .toByteArray()
        val inbound = SensorCodec.decodeInbound(GalMessage.Sensor.SENSOR_ERROR, error)
        assertTrue(inbound is InboundSensor.Error)
        assertEquals(GalMessage.Media.ACK, GalMessage.Sensor.SENSOR_ERROR)
    }

    @Test
    fun `garbage on ch7 is observed, never fatal`() {
        // Wrong type, truncated response, and an uninitialized response all
        // decode to Observed: the pump logs them and carries on.
        val observed = SensorCodec.decodeInbound(GalMessage.Sensor.SENSOR_BATCH, byteArrayOf(1, 2, 3))
        assertEquals(InboundSensor.Observed(GalMessage.Sensor.SENSOR_BATCH), observed)
        assertEquals(
            InboundSensor.Observed(GalMessage.Sensor.SENSOR_RESPONSE),
            SensorCodec.decodeInbound(GalMessage.Sensor.SENSOR_RESPONSE, byteArrayOf(0x08)),
        )
        assertEquals(
            InboundSensor.Observed(GalMessage.Sensor.SENSOR_RESPONSE),
            SensorCodec.decodeInbound(
                GalMessage.Sensor.SENSOR_RESPONSE,
                SensorResponse.newBuilder().buildPartial().toByteArray(),
            ),
        )
    }

    @Test
    fun `ch7 types sit in the service range`() {
        val ids = setOf(
            GalMessage.Sensor.SENSOR_REQUEST,
            GalMessage.Sensor.SENSOR_RESPONSE,
            GalMessage.Sensor.SENSOR_BATCH,
            GalMessage.Sensor.SENSOR_ERROR,
        )
        assertEquals(4, ids.size)
        assertTrue(ids.all { it in 0x8000..0xFFFF })
    }

    @Test
    fun `the new envelope fields pin their numbers`() {
        // MA-defined envelope numbering (rpm=7 ... raw_ev_trip=25): a
        // round-trip passes with a wrong number, so pin the tags -- field 7
        // length-delimited is 0x3A.
        val bytes = SensorEvent.newBuilder()
            .setSensorType(SensorType.RPM)
            .setRpm(com.vayunmathur.auto.protocol.gal.RpmReading.newBuilder().setField1(1))
            .build()
            .toByteArray()
        assertContentEquals(
            byteArrayOf(
                0x08, 0x04, // sensor_type, varint, RPM = 4
                0x3A, 0x02, // rpm, length-delimited, 2 bytes
                0x08, 0x01, //   RpmReading field 1, 1
            ),
            bytes,
        )
    }

    @Test
    fun `every subscribed type encodes its own number in field 1`() {
        // A compass subscribe is 08 02, gear is 08 08, raw EV trip is 08 1A:
        // off-by-one here subscribes the wrong sensor on a real head unit.
        for (type in SensorCodec.STUB_SUBSCRIPTIONS) {
            val (wireType, payload) = SensorCodec.encodeSubscribe(type)
            assertEquals(GalMessage.Sensor.SENSOR_REQUEST, wireType)
            assertEquals(
                type,
                SensorRequest.parseFrom(payload).sensorType,
                "subscribe for $type did not round-trip",
            )
        }
        val (_, compass) = SensorCodec.encodeSubscribe(SensorType.COMPASS)
        assertContentEquals(byteArrayOf(0x08, 0x02), compass)
    }

    @Test
    fun `a compass batch folds its heading`() {
        val batch = SensorCodec.buildCompassBatch(headingDeg = 270)
        val inbound = SensorCodec.decodeInbound(GalMessage.Sensor.SENSOR_BATCH, batch)
        val events = (inbound as? InboundSensor.Readings)?.events
            ?: error("expected readings")
        val snapshot = SensorSnapshot().withEvents(events)
        assertEquals(270, snapshot.compassHeadingDeg)
        // Everything else keeps last-known.
        assertTrue(snapshot.parked)
        assertEquals(0.0, snapshot.speedMps, 1e-9)
        assertNull(snapshot.isNight)
    }

    @Test
    fun `typed readings fold into last-known without touching the stub values`() {
        val seeded = SensorSnapshot(speedMps = 5.0)
        val batch = com.vayunmathur.auto.protocol.gal.SensorBatch.newBuilder()
            .addEvents(
                SensorEvent.newBuilder()
                    .setSensorType(SensorType.RPM)
                    .setRpm(com.vayunmathur.auto.protocol.gal.RpmReading.newBuilder().setField1(3000)),
            )
            .addEvents(
                SensorEvent.newBuilder()
                    .setSensorType(SensorType.PARKING_BRAKE)
                    .setParkingBrake(
                        com.vayunmathur.auto.protocol.gal.ParkingBrakeReading.newBuilder()
                            .setField1(true),
                    ),
            )
            .build()
            .toByteArray()
        val events = (SensorCodec.decodeInbound(GalMessage.Sensor.SENSOR_BATCH, batch)
            as InboundSensor.Readings).events
        val next = seeded.withEvents(events)
        assertEquals(3000, next.rpm?.field1)
        assertEquals(true, next.parkingBrake?.field1)
        assertEquals(5.0, next.speedMps, 1e-9)
        assertTrue(next.parked)
    }

    @Test
    fun `missing-payload types keep last-known`() {
        // VEHICLE_ENERGY_MODEL_DATA (`xgu` unrecovered) and TRAILER_DATA
        // (`xon` empty) carry no payload: explicit branches, no fold.
        val seeded = SensorSnapshot(speedMps = 5.0)
        val events = listOf(
            SensorEvent.newBuilder().setSensorType(SensorType.VEHICLE_ENERGY_MODEL_DATA).build(),
            SensorEvent.newBuilder().setSensorType(SensorType.TRAILER_DATA).build(),
        )
        val next = seeded.withEvents(events)
        assertEquals(5.0, next.speedMps, 1e-9)
        assertNull(next.rawVehicleEnergy)
    }

    @Test
    fun `an unanswered subscribe times out once with the timeout status`() {
        val tracker = SensorRequestTracker()
        tracker.markRequested(SensorType.LOCATION, nowMs = 0L)

        assertTrue(tracker.takeTimedOut(nowMs = 1_999L).isEmpty(), "not yet 2 s")
        val timedOut = tracker.takeTimedOut(nowMs = 2_000L)
        assertEquals(
            listOf(InboundSensor.Error(SensorType.LOCATION, SensorCodec.TIMEOUT_STATUS)),
            timedOut,
        )
        // Reported once: a still-silent head unit must be re-marked.
        assertTrue(tracker.takeTimedOut(nowMs = 10_000L).isEmpty())
    }

    @Test
    fun `an answered subscribe never times out`() {
        val tracker = SensorRequestTracker()
        tracker.markRequested(SensorType.SPEED, nowMs = 0L)
        tracker.markAnswered(SensorType.SPEED)

        assertTrue(tracker.takeTimedOut(nowMs = 60_000L).isEmpty())
    }

    @Test
    fun `re-sending restarts the timeout`() {
        val tracker = SensorRequestTracker()
        tracker.markRequested(SensorType.COMPASS, nowMs = 0L)
        tracker.markRequested(SensorType.COMPASS, nowMs = 1_500L)

        assertTrue(tracker.takeTimedOut(nowMs = 3_000L).isEmpty(), "restarted at 1500")
        assertEquals(1, tracker.takeTimedOut(nowMs = 3_500L).size)
    }

    // ---- recovered reading payloads: exact bytes pin the teardown layouts ----

    @Test
    fun `rpm pins required field 1`() {
        // `xnq`: required int32 field 1. 3000 is varint B8 17.
        val bytes = com.vayunmathur.auto.protocol.gal.RpmReading.newBuilder()
            .setField1(3000)
            .build()
            .toByteArray()
        assertContentEquals(byteArrayOf(0x08, 0xB8.toByte(), 0x17), bytes)
    }

    @Test
    fun `odometer pins required field 1 and optional field 2`() {
        // `xlu`: required int32 field 1, optional int32 field 2.
        val bytes = com.vayunmathur.auto.protocol.gal.OdometerReading.newBuilder()
            .setField1(123456)
            .setField2(7)
            .build()
            .toByteArray()
        // 123456 is varint C0 C4 07.
        assertContentEquals(
            byteArrayOf(0x08, 0xC0.toByte(), 0xC4.toByte(), 0x07, 0x10, 0x07),
            bytes,
        )
    }

    @Test
    fun `fuel pins three optional fields`() {
        // `xjj`: optional int32 fields 1-3.
        val bytes = com.vayunmathur.auto.protocol.gal.FuelReading.newBuilder()
            .setField1(1)
            .setField2(2)
            .setField3(3)
            .build()
            .toByteArray()
        assertContentEquals(byteArrayOf(0x08, 0x01, 0x10, 0x02, 0x18, 0x03), bytes)
    }

    @Test
    fun `parking brake pins required bool field 1`() {
        // `xly`: required bool field 1.
        val bytes = com.vayunmathur.auto.protocol.gal.ParkingBrakeReading.newBuilder()
            .setField1(true)
            .build()
            .toByteArray()
        assertContentEquals(byteArrayOf(0x08, 0x01), bytes)
    }

    @Test
    fun `gear pins required field 1 as a varint`() {
        // `xjl`: required enum field 1; int32 here is the identical varint
        // encoding (the enum type is unidentified, never invented).
        val bytes = com.vayunmathur.auto.protocol.gal.GearReading.newBuilder()
            .setField1(4)
            .build()
            .toByteArray()
        assertContentEquals(byteArrayOf(0x08, 0x04), bytes)
    }

    @Test
    fun `obdii pins optional bytes field 1`() {
        // `xjb`: optional bytes field 1 -- tag 0x0A, length, bytes.
        val bytes = com.vayunmathur.auto.protocol.gal.ObdiiReading.newBuilder()
            .setField1(com.google.protobuf.ByteString.copyFrom(byteArrayOf(0x43, 0x01)))
            .build()
            .toByteArray()
        assertContentEquals(byteArrayOf(0x0A, 0x02, 0x43, 0x01), bytes)
    }

    @Test
    fun `environment pins three optional fields`() {
        // `xjg`: optional int32 fields 1-3.
        val bytes = com.vayunmathur.auto.protocol.gal.EnvironmentReading.newBuilder()
            .setField1(21)
            .setField2(101)
            .setField3(40)
            .build()
            .toByteArray()
        assertContentEquals(
            byteArrayOf(0x08, 0x15, 0x10, 0x65, 0x18, 0x28),
            bytes,
        )
    }

    @Test
    fun `hvac pins two optional fields`() {
        // `xjr`: optional int32 fields 1-2.
        val bytes = com.vayunmathur.auto.protocol.gal.HvacReading.newBuilder()
            .setField1(2)
            .setField2(22)
            .build()
            .toByteArray()
        assertContentEquals(byteArrayOf(0x08, 0x02, 0x10, 0x16), bytes)
    }

    @Test
    fun `dead reckoning pins optional field 1 and unpacked repeated field 2`() {
        // `xja`: optional int32 field 1, repeated (unpacked) int32 field 2 --
        // each entry its own 0x10 tag, no packed header.
        val bytes = com.vayunmathur.auto.protocol.gal.DeadReckoningReading.newBuilder()
            .setField1(1)
            .addField2(2)
            .addField2(3)
            .build()
            .toByteArray()
        assertContentEquals(byteArrayOf(0x08, 0x01, 0x10, 0x02, 0x10, 0x03), bytes)
    }

    @Test
    fun `passenger pins optional bool field 1`() {
        // `xlz`: optional bool field 1.
        val bytes = com.vayunmathur.auto.protocol.gal.PassengerReading.newBuilder()
            .setField1(false)
            .build()
            .toByteArray()
        assertContentEquals(byteArrayOf(0x08, 0x00), bytes)
    }

    @Test
    fun `door pins two optional bools and a repeated bool`() {
        // `xjd`: optional bools 1-2, repeated bool field 3 (tag 0x18 per entry).
        val bytes = com.vayunmathur.auto.protocol.gal.DoorReading.newBuilder()
            .setField1(true)
            .setField2(false)
            .addField3(true)
            .addField3(false)
            .build()
            .toByteArray()
        assertContentEquals(
            byteArrayOf(0x08, 0x01, 0x10, 0x00, 0x18, 0x01, 0x18, 0x00),
            bytes,
        )
    }

    @Test
    fun `light pins two varint fields and a bool`() {
        // `xkg`: optional enums 1-2 (int32 here, identical varints) plus bool 3.
        val bytes = com.vayunmathur.auto.protocol.gal.LightReading.newBuilder()
            .setField1(1)
            .setField2(2)
            .setField3(true)
            .build()
            .toByteArray()
        assertContentEquals(byteArrayOf(0x08, 0x01, 0x10, 0x02, 0x18, 0x01), bytes)
    }

    @Test
    fun `tire pressure pins unpacked repeated field 1`() {
        // `xoj`: repeated (unpacked) int32 field 1, one entry per wheel.
        val bytes = com.vayunmathur.auto.protocol.gal.TirePressureReading.newBuilder()
            .addField1(220)
            .addField1(221)
            .build()
            .toByteArray()
        // 220 is varint DC 01, 221 is DD 01.
        assertContentEquals(
            byteArrayOf(0x08, 0xDC.toByte(), 0x01, 0x08, 0xDD.toByte(), 0x01),
            bytes,
        )
    }

    @Test
    fun `accelerometer pins three optional fields`() {
        // `xgy`: optional int32 fields 1-3 (x/y/z axes).
        val bytes = com.vayunmathur.auto.protocol.gal.AccelerometerReading.newBuilder()
            .setField1(1)
            .setField2(2)
            .setField3(3)
            .build()
            .toByteArray()
        assertContentEquals(byteArrayOf(0x08, 0x01, 0x10, 0x02, 0x18, 0x03), bytes)
    }

    @Test
    fun `gyroscope pins three optional fields`() {
        // `xjp`: optional int32 fields 1-3 (x/y/z axes).
        val bytes = com.vayunmathur.auto.protocol.gal.GyroscopeReading.newBuilder()
            .setField1(4)
            .setField2(5)
            .setField3(6)
            .build()
            .toByteArray()
        assertContentEquals(byteArrayOf(0x08, 0x04, 0x10, 0x05, 0x18, 0x06), bytes)
    }

    @Test
    fun `gps satellite pins its header and repeated entries`() {
        // `xjo`: required int32 f1, optional int32 f2, repeated `xjn` f3.
        // `xjn`: required int32 f1-2, required bool f3, optional int32 f4-5.
        val bytes = com.vayunmathur.auto.protocol.gal.GpsSatelliteReading.newBuilder()
            .setField1(3)
            .setField2(7)
            .addField3(
                com.vayunmathur.auto.protocol.gal.GpsSatelliteEntry.newBuilder()
                    .setField1(5)
                    .setField2(42)
                    .setField3(true)
                    .setField4(1)
                    .setField5(2),
            )
            .build()
            .toByteArray()
        assertContentEquals(
            byteArrayOf(
                0x08, 0x03, // f1, varint, 3
                0x10, 0x07, // f2, varint, 7
                0x1A, 0x0A, // f3, length-delimited, 10 bytes
                0x08, 0x05, //   entry f1, 5
                0x10, 0x2A, //   entry f2, 42
                0x18, 0x01, //   entry f3, true
                0x20, 0x01, //   entry f4, 1
                0x28, 0x02, //   entry f5, 2
            ),
            bytes,
        )
    }

    @Test
    fun `toll card pins required bool field 1`() {
        // `xok`: required bool field 1.
        val bytes = com.vayunmathur.auto.protocol.gal.TollCardReading.newBuilder()
            .setField1(false)
            .build()
            .toByteArray()
        assertContentEquals(byteArrayOf(0x08, 0x00), bytes)
    }

    @Test
    fun `raw vehicle energy pins optional bytes field 1`() {
        // `xnk`: optional bytes field 1 (opaque model blob).
        val bytes = com.vayunmathur.auto.protocol.gal.RawVehicleEnergyReading.newBuilder()
            .setField1(com.google.protobuf.ByteString.copyFrom(byteArrayOf(0x07)))
            .build()
            .toByteArray()
        assertContentEquals(byteArrayOf(0x0A, 0x01, 0x07), bytes)
    }

    @Test
    fun `raw ev trip pins optional bytes field 1`() {
        // `xni`: optional bytes field 1 (opaque settings blob).
        val bytes = com.vayunmathur.auto.protocol.gal.RawEvTripReading.newBuilder()
            .setField1(com.google.protobuf.ByteString.copyFrom(byteArrayOf(0x09, 0x08)))
            .build()
            .toByteArray()
        assertContentEquals(byteArrayOf(0x0A, 0x02, 0x09, 0x08), bytes)
    }
}
