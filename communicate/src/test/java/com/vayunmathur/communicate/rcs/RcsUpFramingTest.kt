package com.vayunmathur.communicate.rcs

import com.vayunmathur.communicate.data.rcs.ImdnDisposition
import com.vayunmathur.communicate.data.rcs.buildImdnBody
import com.vayunmathur.communicate.data.rcs.buildIsComposingBody
import com.vayunmathur.communicate.data.rcs.extractImdnMessageId
import com.vayunmathur.communicate.data.rcs.e2e.RcsKeyDirectory
import com.vayunmathur.communicate.data.rcs.parseImdnBody
import com.vayunmathur.communicate.data.rcs.parseIsComposingBody
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
}
