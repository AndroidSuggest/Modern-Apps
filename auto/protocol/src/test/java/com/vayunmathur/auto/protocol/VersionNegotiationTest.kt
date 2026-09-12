package com.vayunmathur.auto.protocol

import com.vayunmathur.auto.protocol.gal.MessageStatus
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionNegotiationTest {

    @Test
    fun `a version request is two raw big-endian shorts`() {
        // Not protobuf. A protobuf parse of these four bytes would be garbage.
        val version = VersionNegotiation.parseRequest(byteArrayOf(0x00, 0x01, 0x00, 0x06))
        assertEquals(GalVersion(1, 6), version)
    }

    @Test
    fun `a version response is major, minor, status as raw shorts`() {
        val payload = VersionNegotiation.buildResponse(
            GalVersion(1, 7),
            MessageStatus.STATUS_SUCCESS.number,
        )
        assertContentEquals(byteArrayOf(0x00, 0x01, 0x00, 0x07, 0x00, 0x00), payload)
    }

    @Test
    fun `a negative status wraps to its unsigned short form`() {
        // STATUS_NO_COMPATIBLE_VERSION is -1 and goes out as 0xFFFF.
        val payload = VersionNegotiation.buildResponse(
            GalVersion(1, 7),
            MessageStatus.STATUS_NO_COMPATIBLE_VERSION.number,
        )
        assertContentEquals(
            byteArrayOf(0x00, 0x01, 0x00, 0x07, 0xFF.toByte(), 0xFF.toByte()),
            payload,
        )
    }

    @Test
    fun `the full response frame matches gearhead's eight bytes`() {
        // Gearhead allocates 8: the 2-byte message type plus three shorts.
        val frame = MessageCodec.encode(
            GalMessage.Control.VERSION_RESPONSE,
            VersionNegotiation.buildResponse(GalVersion(1, 7), MessageStatus.STATUS_SUCCESS.number),
        )
        assertEquals(8, frame.size)
        assertContentEquals(
            byteArrayOf(0x00, 0x02, 0x00, 0x01, 0x00, 0x07, 0x00, 0x00),
            frame,
        )
    }

    @Test
    fun `we accept anything at or below what we speak`() {
        assertEquals(GalVersion(1, 6), VersionNegotiation.negotiate(GalVersion(1, 6)))
        assertEquals(GalVersion(1, 7), VersionNegotiation.negotiate(GalVersion(1, 7)))
        assertEquals(GalVersion(1, 1), VersionNegotiation.negotiate(GalVersion(1, 1)))
    }

    @Test
    fun `we refuse a head unit asking for more than we speak`() {
        assertNull(VersionNegotiation.negotiate(GalVersion(1, 8)))
        assertNull(VersionNegotiation.negotiate(GalVersion(2, 0)))
    }

    @Test
    fun `versions order by major then minor`() {
        assertTrue(GalVersion(1, 7) > GalVersion(1, 6))
        assertTrue(GalVersion(2, 0) > GalVersion(1, 99))
        assertTrue(GalVersion(1, 6) >= VersionNegotiation.MINIMUM_FOR_DISCOVERY)
        assertTrue(GalVersion(1, 5) < VersionNegotiation.MINIMUM_FOR_DISCOVERY)
    }

    @Test
    fun `a truncated request is rejected rather than misread`() {
        assertFailsWith<IllegalArgumentException> {
            VersionNegotiation.parseRequest(byteArrayOf(0x00, 0x01))
        }
    }

    @Test
    fun `a response round-trips`() {
        val payload = VersionNegotiation.buildResponse(GalVersion(1, 6), -1)
        val (version, status, _) = VersionNegotiation.parseResponse(payload)
        assertEquals(GalVersion(1, 6), version)
        assertEquals(-1, status)
    }
}
