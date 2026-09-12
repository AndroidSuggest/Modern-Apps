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
        assertEquals(
            setOf(
                SensorType.LOCATION,
                SensorType.NIGHT_MODE,
                SensorType.SPEED,
                SensorType.DRIVING_STATUS_DATA,
            ),
            SensorCodec.STUB_SUBSCRIPTIONS.toSet(),
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
}
