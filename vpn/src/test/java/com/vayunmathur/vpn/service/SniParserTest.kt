package com.vayunmathur.vpn.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [SniParser] reads attacker-influenced bytes straight off the tunnel, so as much of this
 * is about surviving malformed input without throwing or over-reading as it is about
 * parsing valid ClientHellos.
 */
class SniParserTest {

    // --- ClientHello construction helpers ---

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), (v and 0xFF).toByte())

    /**
     * A minimal but structurally valid TLS 1.2 ClientHello carrying [hostNames] in the
     * server_name extension. [extraExtensionsBefore] pads the extension list so the SNI
     * extension isn't always the first one.
     */
    private fun clientHello(
        hostNames: List<String>,
        extraExtensionsBefore: Int = 0,
        sessionIdLen: Int = 0,
    ): ByteArray {
        val sniList = hostNames.fold(ByteArray(0)) { acc, host ->
            val h = host.toByteArray(Charsets.UTF_8)
            acc + byteArrayOf(0) + u16(h.size) + h        // name_type=host_name, length, name
        }
        val sniExtBody = u16(sniList.size) + sniList
        val sniExt = u16(0) + u16(sniExtBody.size) + sniExtBody

        // Filler extensions use type 0x1234 with a 2-byte body.
        var others = ByteArray(0)
        repeat(extraExtensionsBefore) { others += u16(0x1234) + u16(2) + byteArrayOf(0, 0) }

        val extensions = others + sniExt
        val body =
            u16(0x0303) +                                  // client_version
            ByteArray(32) +                                // random
            byteArrayOf(sessionIdLen.toByte()) + ByteArray(sessionIdLen) +
            u16(2) + byteArrayOf(0x13, 0x01) +             // cipher_suites
            byteArrayOf(1, 0) +                            // compression_methods
            u16(extensions.size) + extensions

        val handshake = byteArrayOf(1) +                   // ClientHello
            byteArrayOf((body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()) +
            body
        return byteArrayOf(0x16) + u16(0x0301) + u16(handshake.size) + handshake
    }

    private fun parse(packet: ByteArray, offset: Int = 0, len: Int = packet.size - offset) =
        SniParser.extractSni(packet, offset, len)

    // --- happy path ---

    @Test
    fun extractsHostNameFromAValidClientHello() {
        assertEquals("example.com", parse(clientHello(listOf("example.com"))))
    }

    @Test
    fun extractsHostNameWhenSniIsNotTheFirstExtension() {
        assertEquals(
            "cdn.example.org",
            parse(clientHello(listOf("cdn.example.org"), extraExtensionsBefore = 3)),
        )
    }

    @Test
    fun handlesANonEmptySessionId() {
        assertEquals("example.com", parse(clientHello(listOf("example.com"), sessionIdLen = 32)))
    }

    @Test
    fun parsesAtAnOffsetIntoALargerPacket() {
        val hello = clientHello(listOf("offset.example.com"))
        val ipAndTcpHeaders = ByteArray(40) { 0x41 }
        val packet = ipAndTcpHeaders + hello
        assertEquals("offset.example.com", parse(packet, offset = 40, len = hello.size))
    }

    @Test
    fun returnsTheFirstHostNameWhenSeveralArePresent() {
        assertEquals("first.example.com", parse(clientHello(listOf("first.example.com", "second.example.com"))))
    }

    @Test
    fun acceptsALongHostName() {
        val host = "a".repeat(60) + ".example.com"
        assertEquals(host, parse(clientHello(listOf(host))))
    }

    // --- rejection / robustness ---

    @Test
    fun rejectsPayloadsShorterThanATlsRecordHeader() {
        assertNull(SniParser.extractSni(ByteArray(9), 0, 9))
        assertNull(SniParser.extractSni(ByteArray(0), 0, 0))
    }

    @Test
    fun rejectsAPayloadLengthThatOverrunsTheBuffer() {
        val hello = clientHello(listOf("example.com"))
        assertNull(SniParser.extractSni(hello, 0, hello.size + 50))
    }

    @Test
    fun rejectsNonHandshakeRecords() {
        val hello = clientHello(listOf("example.com"))
        hello[0] = 0x17 // application_data
        assertNull(parse(hello))
    }

    @Test
    fun rejectsHandshakeMessagesThatAreNotClientHello() {
        val hello = clientHello(listOf("example.com"))
        hello[5] = 2 // ServerHello
        assertNull(parse(hello))
    }

    @Test
    fun rejectsAHostNameWithoutADotRatherThanReturningGarbage() {
        // The parser requires a dot so a stray byte run isn't reported as a domain.
        assertNull(parse(clientHello(listOf("localhost"))))
    }

    @Test
    fun rejectsAnEmptyHostName() {
        assertNull(parse(clientHello(listOf(""))))
    }

    @Test
    fun truncatedClientHelloDoesNotThrow() {
        // Every prefix of a valid hello must return null rather than blow up: a real
        // tunnel sees ClientHellos split across TCP segments all the time.
        val hello = clientHello(listOf("example.com"))
        for (cut in 1 until hello.size) {
            val truncated = hello.copyOf(cut)
            assertNull(parse(truncated), "prefix of length $cut should not parse")
        }
    }

    @Test
    fun corruptedLengthFieldsDoNotThrow() {
        // Flip each byte to 0xFF in turn — an oversized length must be caught by the
        // bounds checks, not by reading past the array.
        val original = clientHello(listOf("example.com"))
        for (i in original.indices) {
            val mutated = original.copyOf()
            mutated[i] = 0xFF.toByte()
            SniParser.extractSni(mutated, 0, mutated.size) // must not throw
        }
    }

    @Test
    fun randomBytesDoNotThrow() {
        val rnd = java.util.Random(1234)
        repeat(500) {
            val buf = ByteArray(64).also { b -> rnd.nextBytes(b) }
            buf[0] = 0x16 // force it past the record-type check to reach the real parsing
            SniParser.extractSni(buf, 0, buf.size) // must not throw
        }
    }
}
