package com.vayunmathur.communicate.whatsapp

import com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Derivation vectors for the call-key crypto (Phase D 3b). Pure (java.security only), no Android.
 */
class WhatsAppCallCryptoTest {

    private val callKey = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun callKeyIs32Bytes() {
        assertEquals(32, WhatsAppCallCrypto.generateCallKey().size)
    }

    @Test
    fun mediaKeys_areDeterministicWithCorrectLengths() {
        val a = WhatsAppCallCrypto.deriveMediaKeys(callKey, "call-123")
        val b = WhatsAppCallCrypto.deriveMediaKeys(callKey, "call-123")
        assertEquals(16, a.encKey.size)
        assertEquals(14, a.salt.size)
        assertEquals(32, a.authKey.size)
        assertTrue(a.encKey.contentEquals(b.encKey))
        assertTrue(a.salt.contentEquals(b.salt))
        assertTrue(a.authKey.contentEquals(b.authKey))
        assertEquals(a.ssrc, b.ssrc)
    }

    @Test
    fun differentCallId_yieldsDifferentKeys() {
        val a = WhatsAppCallCrypto.deriveMediaKeys(callKey, "call-A")
        val b = WhatsAppCallCrypto.deriveMediaKeys(callKey, "call-B")
        assertFalse(a.encKey.contentEquals(b.encKey))
        assertTrue(a.ssrc != b.ssrc || !a.salt.contentEquals(b.salt))
    }

    @Test
    fun ssrc_isNonNegativeAndDeterministic() {
        val s1 = WhatsAppCallCrypto.deriveSsrc(callKey, "call-1")
        val s2 = WhatsAppCallCrypto.deriveSsrc(callKey, "call-1")
        assertEquals(s1, s2)
        assertTrue(s1 >= 0)
    }

    @Test
    fun rekey_differsFromOriginalAndIs32Bytes() {
        val rk = WhatsAppCallCrypto.rekey(callKey, "rekey-7")
        assertEquals(32, rk.size)
        assertFalse(rk.contentEquals(callKey))
        // Deterministic for the same rekey id.
        assertTrue(rk.contentEquals(WhatsAppCallCrypto.rekey(callKey, "rekey-7")))
    }
}
