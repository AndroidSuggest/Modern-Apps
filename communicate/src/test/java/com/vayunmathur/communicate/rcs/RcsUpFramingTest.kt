package com.vayunmathur.communicate.rcs

import com.vayunmathur.communicate.data.rcs.ImdnDisposition
import com.vayunmathur.communicate.data.rcs.MsrpSetup
import com.vayunmathur.communicate.data.rcs.RcsFileTransferHttp
import com.vayunmathur.communicate.data.rcs.RcsGbaAuth
import com.vayunmathur.communicate.data.rcs.RcsMsrp
import com.vayunmathur.communicate.data.rcs.RcsMsrpListen
import com.vayunmathur.communicate.data.rcs.RcsMsrpTls
import com.vayunmathur.communicate.data.rcs.RcsSessionManager
import com.vayunmathur.communicate.data.rcs.buildEditBody
import com.vayunmathur.communicate.data.rcs.buildGeopushBody
import com.vayunmathur.communicate.data.rcs.buildImdnBody
import com.vayunmathur.communicate.data.rcs.buildIsComposingBody
import com.vayunmathur.communicate.data.rcs.buildRevokeBody
import com.vayunmathur.communicate.data.rcs.chunkLargeMessage
import com.vayunmathur.communicate.data.rcs.extractImdnMessageId
import com.vayunmathur.communicate.data.rcs.e2e.RcsKeyDirectory
import com.vayunmathur.communicate.data.rcs.parseChunkHeader
import com.vayunmathur.communicate.data.rcs.parseEditBody
import com.vayunmathur.communicate.data.rcs.parseGeopushBody
import com.vayunmathur.communicate.data.rcs.parseImdnBody
import com.vayunmathur.communicate.data.rcs.parseIsComposingBody
import com.vayunmathur.communicate.data.rcs.parseRevokeBody
import com.vayunmathur.communicate.data.rcs.reassembleLargeMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the RCS Universal Profile framing: IMDN receipts (RFC 5438),
 * is-composing (RFC 3994), and key-package publication parsing. Pure string
 * functions — no Android framework needed.
 */
class RcsUpFramingTest {
    @Test
    fun imdnRoundTrip() {
        val body = buildImdnBody("msg-123", ImdnDisposition.Delivered)
        val parsed = parseImdnBody(body)
        assertEquals("msg-123" to ImdnDisposition.Delivered, parsed)
    }

    @Test
    fun imdnDisplayAndFailed() {
        assertEquals(
            "a" to ImdnDisposition.Displayed,
            parseImdnBody(buildImdnBody("a", ImdnDisposition.Displayed)),
        )
        assertEquals(
            "b" to ImdnDisposition.Failed,
            parseImdnBody(buildImdnBody("b", ImdnDisposition.Failed)),
        )
    }

    @Test
    fun imdnRejectsNonReports() {
        assertNull(parseImdnBody("hello world"))
        assertNull(parseImdnBody("<imdn><message-id>x</message-id></imdn>"))
    }

    @Test
    fun imdnMessageIdExtraction() {
        val cpim = "NS: imdn <urn:ietf:params:imdn>\r\n" +
            "imdn.Message-ID: Test_42\r\n" +
            "Content-Type: text/plain\r\n\r\nhello"
        assertEquals("Test_42", extractImdnMessageId(cpim))
        assertNull(extractImdnMessageId("no headers here"))
    }

    @Test
    fun isComposingRoundTrip() {
        assertTrue(parseIsComposingBody(buildIsComposingBody(true)) == true)
        assertTrue(parseIsComposingBody(buildIsComposingBody(false)) == false)
        assertNull(parseIsComposingBody("plain text"))
    }

    @Test
    fun keyPackageParse() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val body = "Content-Type: application/x-rcs-keypackage\r\n\r\n$b64"
        val parsed = RcsKeyDirectory.parsePublished(body)
        assertTrue(parsed != null && parsed.contentEquals(bytes))
    }

    @Test
    fun keyPackageRejects() {
        assertNull(RcsKeyDirectory.parsePublished("hello"))
        assertFalse(
            RcsKeyDirectory.parsePublished(
                "Content-Type: application/x-rcs-keypackage\r\n\r\n!!!not-base64!!!",
            ) != null,
        )
    }

    @Test
    fun geopushRoundTrip() {
        val body = buildGeopushBody(37.42, -122.08, "HQ")
        val parsed = parseGeopushBody(body)
        assertEquals(Triple(37.42, -122.08, "HQ"), parsed)
        assertNull(parseGeopushBody("plain text"))
    }

    @Test
    fun largeMessageChunksReassemble() {
        val text = "x".repeat(20000)
        val chunks = chunkLargeMessage("m1", text)
        assertTrue(chunks.size > 1)
        val map = mutableMapOf<Int, String>()
        var total = 0
        for (chunk in chunks) {
            val (id, part, t) = parseChunkHeader(chunk) ?: error("no header")
            assertEquals("m1", id)
            total = t
            map[part] = chunk.substringAfter("\r\n\r\n")
        }
        assertEquals(text, reassembleLargeMessage(map, total))
        assertNull(reassembleLargeMessage(mapOf(1 to "a"), 2))
    }

    @Test
    fun smallMessageNotChunked() {
        val chunks = chunkLargeMessage("m2", "hi")
        assertEquals(listOf("hi"), chunks)
    }

    @Test
    fun revokeRoundTrip() {
        val body = buildRevokeBody("orig-9")
        assertEquals("orig-9", parseRevokeBody(body))
        assertNull(parseRevokeBody("hello"))
    }

    @Test
    fun editRoundTrip() {
        val (id, text) = parseEditBody(buildEditBody("orig-7", "fixed")) ?: error("no edit")
        assertEquals("orig-7", id)
        assertEquals("fixed", text)
        assertNull(parseEditBody("plain"))
    }

    @Test
    fun gbaDigestKnownAnswer() {
        // RFC 2617 §3.5 known-answer vector: user "Mufasa", password
        // "Circle Of Life", realm "testrealm@host.com", nonce
        // "dcd98b7102dd2f0e8b11d0f600bfb0c093".
        val response = RcsGbaAuth.digestResponse(
            username = "Mufasa",
            password = "Circle Of Life",
            realm = "testrealm@host.com",
            nonce = "dcd98b7102dd2f0e8b11d0f600bfb0c093",
            method = "GET",
            uri = "/dir/index.html",
            cnonce = "0a4f113b",
        )
        assertEquals("6629fae49393a05397450978507c4ef1", response)
    }

    @Test
    fun focusBookkeepingTracksMembers() {
        // No focus hosted: lookups are null, leave is a no-op.
        assertNull(RcsSessionManager.hostedFocusFor("nope"))
        assertNull(RcsSessionManager.focusMembers("conf:missing@rcs.local"))
        RcsSessionManager.noteFocusLeave("conf:missing@rcs.local", "+1555")
    }

    @Test
    fun sdpSetupParsesAllRoles() {
        assertEquals(
            MsrpSetup.ACTIVE,
            RcsSessionManager.parseSdpSetup("m=message 2855 TCP/MSRP *\r\na=setup:active\r\n"),
        )
        assertEquals(
            MsrpSetup.PASSIVE,
            RcsSessionManager.parseSdpSetup("a=setup:passive"),
        )
        assertEquals(
            MsrpSetup.ACTPASS,
            RcsSessionManager.parseSdpSetup("a=setup:actpass"),
        )
        assertNull(RcsSessionManager.parseSdpSetup("m=message 2855 TCP/MSRP *"))
        assertNull(RcsSessionManager.parseSdpSetup(null))
        assertNull(RcsSessionManager.parseSdpSetup("a=setup:bogus"))
    }

    @Test
    fun acceptedHeadReplays() {
        val head = RcsMsrpListen.AcceptedHead(
            listOf("MSRP abc SEND", "To-Path: msrp://1.2.3.4:2855/x;tcp", "From-Path: msrp://5.6.7.8:9/y;tcp"),
        )
        val replayed = head.replayBytes().toString(Charsets.UTF_8)
        assertTrue(replayed.startsWith("MSRP abc SEND\r\n"))
        assertTrue(replayed.contains("To-Path: msrp://1.2.3.4:2855/x;tcp\r\n"))
        assertTrue(replayed.endsWith("\r\n"))
    }

    @Test
    fun contentServerParses() {
        val xml = "<rcs><ftHTTPCSURI>https://ft.example.com/upload</ftHTTPCSURI></rcs>"
            .toByteArray(Charsets.UTF_8)
        assertEquals(
            "https://ft.example.com/upload",
            RcsFileTransferHttp.parseContentServer(xml),
        )
        assertNull(RcsFileTransferHttp.parseContentServer("<rcs/>".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun sweepDropsOnlyStale() {
        // Empty manager: nothing to drop, no crash.
        assertTrue(RcsSessionManager.sweepStaleSessions().isEmpty())
    }

    @Test
    fun fingerprintParses() {
        val sdp = "m=message 2855 TCP/TLS/MSRP *\r\n" +
            "a=path:msrps://10.0.0.1:2855/abc;tcp\r\n" +
            "a=fingerprint:SHA-256 AA:BB:CC:01\r\n"
        val (hash, value) = RcsMsrpTls.parseFingerprint(sdp) ?: error("no fingerprint")
        assertEquals("SHA-256", hash)
        assertEquals("AA:BB:CC:01", value)
        assertNull(RcsMsrpTls.parseFingerprint("m=message 2855 TCP/MSRP *"))
        assertNull(RcsMsrpTls.parseFingerprint(null))
    }

    @Test
    fun secureSdpDetection() {
        assertTrue(
            RcsSessionManager.isSecureSdp("m=message 2855 TCP/TLS/MSRP *\r\na=setup:actpass\r\n"),
        )
        assertTrue(
            RcsSessionManager.isSecureSdp("a=path:msrps://10.0.0.1:9/x;tcp\r\n"),
        )
        assertTrue(
            RcsSessionManager.isSecureSdp("a=fingerprint:SHA-256 AA:BB\r\n"),
        )
        assertFalse(
            RcsSessionManager.isSecureSdp("m=message 2855 TCP/MSRP *\r\na=setup:active\r\n"),
        )
        assertFalse(RcsSessionManager.isSecureSdp(null))
    }

    @Test
    fun securePathScheme() {
        assertTrue(RcsMsrp.isSecurePath("msrps://10.0.0.1:2855/abc;tcp"))
        assertTrue(RcsMsrp.isSecurePath("MSRPS://10.0.0.1:2855/abc;tcp"))
        assertFalse(RcsMsrp.isSecurePath("msrp://10.0.0.1:2855/abc;tcp"))
        assertFalse(RcsMsrp.isSecurePath(null))
        assertFalse(RcsMsrp.isSecurePath(""))
        // Scheme-agnostic host/port parsing still works for msrps.
        assertEquals(
            "10.0.0.1" to 2855,
            RcsMsrp.parseMsrpPath("msrps://10.0.0.1:2855/abc;tcp"),
        )
    }

    @Test
    fun selfSignedCertParsesAndVerifies() {
        // Hand-rolled DER must decode as X.509, verify with its own key, and
        // fingerprint-match its DER bytes.
        val kpg = java.security.KeyPairGenerator.getInstance("EC")
        kpg.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        val now = System.currentTimeMillis()
        val der = RcsMsrpTls.selfSignedCert(
            publicKey = kp.public.encoded,
            privateKey = kp.private,
            notBeforeMs = now - 1000,
            notAfterMs = now + 3600_000,
            commonName = "test",
        )
        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(der.inputStream()) as java.security.cert.X509Certificate
        cert.verify(kp.public)
        assertEquals("CN=test", cert.subjectX500Principal.name)
        val fp = RcsMsrpTls.fingerprintOf(der)
        assertTrue(fp.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
        // Fingerprint binds the exact bytes: flipping one flips the print.
        val mutated = der.copyOf().also { it[der.size - 1] = (it[der.size - 1] + 1).toByte() }
        assertFalse(RcsMsrpTls.fingerprintOf(mutated) == fp)
    }
}
