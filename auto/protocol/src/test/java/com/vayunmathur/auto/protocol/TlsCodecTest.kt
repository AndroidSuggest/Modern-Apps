package com.vayunmathur.auto.protocol

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TlsCodecTest {

    private fun connected(): Pair<TlsCodec, TlsCodec> {
        val context = TestTls.context()
        val server = GalCredential.serverEngine(context)
        val car = TestTls.carEngine(context)
        TestTls.handshake(server, car)
        return TlsCodec(server) to TlsCodec(car)
    }

    @Test
    fun `application data survives phone to car`() {
        val (phone, car) = connected()
        val payload = Random(1).nextBytes(512)

        assertContentEquals(payload, car.unwrap(phone.wrap(payload)))
    }

    @Test
    fun `application data survives car to phone`() {
        val (phone, car) = connected()
        val payload = Random(2).nextBytes(512)

        assertContentEquals(payload, phone.unwrap(car.wrap(payload)))
    }

    @Test
    fun `wrapping actually encrypts`() {
        val (phone, _) = connected()
        // A recognisable plaintext that must not appear on the wire.
        val payload = "SERVICE_DISCOVERY".encodeToByteArray()

        val record = phone.wrap(payload)

        assertFalse(
            record.toList().windowed(payload.size).any { it == payload.toList() },
            "plaintext appeared in the TLS record",
        )
    }

    @Test
    fun `a full frame payload round-trips`() {
        // The largest plaintext a default frame carries. This is the case the 16128 fragment
        // size exists to keep inside the record buffer.
        val (phone, car) = connected()
        val payload = Random(3).nextBytes(
            FrameHeader.DEFAULT_MAX_FRAME_SIZE - FrameHeader.LONG_HEADER_SIZE,
        )

        assertContentEquals(payload, car.unwrap(phone.wrap(payload)))
    }

    @Test
    fun `an empty payload round-trips`() {
        val (phone, car) = connected()
        assertContentEquals(ByteArray(0), car.unwrap(phone.wrap(ByteArray(0))))
    }

    @Test
    fun `successive records stay in step`() {
        // TLS is stateful: records must be unwrapped in the order they were wrapped.
        val (phone, car) = connected()
        val payloads = (1..5).map { Random(it).nextBytes(100 * it) }

        val records = payloads.map { phone.wrap(it) }
        val decoded = records.map { car.unwrap(it) }

        payloads.zip(decoded).forEach { (sent, received) ->
            assertContentEquals(sent, received)
        }
    }

    @Test
    fun `the codec drives real frames end to end`() {
        val (phone, car) = connected()
        val writer = FrameWriter(encrypt = phone::wrap)
        val reader = FrameReader(decrypt = car::unwrap)
        val body = Random(9).nextBytes(40_000)

        val collected = writer
            .frame(GalService.VIDEO_SINK.id, body, encrypted = true)
            .flatMap { reader.offer(it) }

        assertTrue(collected.size == 1, "expected one reassembled message")
        assertContentEquals(body, collected.single().payload)
    }
}
