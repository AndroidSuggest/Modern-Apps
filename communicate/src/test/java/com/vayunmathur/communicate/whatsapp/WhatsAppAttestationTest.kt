package com.vayunmathur.communicate.whatsapp

import com.vayunmathur.communicate.data.whatsapp.registration.RegistrationIntegrity
import com.vayunmathur.communicate.data.whatsapp.registration.WhatsAppPqPreKey
import java.nio.ByteBuffer
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wire-shape assertions for the attestation integrity encoders + PQ signing-input framing
 * (Phase B 2a/2d). Pure (java.util.Base64 + java.security), no Android runtime required.
 */
class WhatsAppAttestationTest {

    private val dec = Base64.getDecoder()

    @Test
    fun aid_isBase64Sha256_44chars_deterministic() {
        val a = RegistrationIntegrity.aidOf("abc123deviceid")
        val b = RegistrationIntegrity.aidOf("abc123deviceid")
        assertEquals(a, b)
        assertEquals(44, a.length) // base64 of a 32-byte SHA-256 digest
        assertEquals(32, dec.decode(a).size)
    }

    @Test
    fun permissionsHash_isOrderIndependent() {
        val a = RegistrationIntegrity.permissionsHashOf(listOf("B", "A", "C"))
        val b = RegistrationIntegrity.permissionsHashOf(listOf("C", "B", "A"))
        assertEquals(a, b)
        assertEquals(32, dec.decode(a).size)
    }

    @Test
    fun tField_isBigEndianInt64Base64() {
        val t = 1_786_666_203L
        val decoded = dec.decode(RegistrationIntegrity.tField(t))
        assertEquals(8, decoded.size)
        assertEquals(t, ByteBuffer.wrap(decoded).long)
    }

    @Test
    fun jsonEncoders_matchSpecShapes() {
        assertEquals("""{"sv":false,"sb":true}""", RegistrationIntegrity.emulationJson(false, true))
        assertEquals(
            """{"mp":true,"mu":false,"ae":10,"ap":20,"ai":0}""",
            RegistrationIntegrity.automationJson(true, false, 10, 20, 0),
        )
        assertEquals("""{"em":"AAAA"}""", RegistrationIntegrity.nativeSignalsJson("AAAA"))
    }

    @Test
    fun pqSigningInput_isTypeBytePlusPublicKey() {
        val pub = ByteArray(1568) { (it and 0xFF).toByte() }
        val input = WhatsAppPqPreKey.signingInput(pub)
        assertEquals(1 + 1568, input.size)
        assertEquals(0x08.toByte(), input[0]) // KEY_TYPE_KYBER
        assertContentEquals(pub, input.copyOfRange(1, input.size))
    }

    @Test
    fun pqSigningInput_typeByteDistinguishesFromCurve() {
        // Curve25519 signed-prekey uses 0x05; the PQ prekey MUST use 0x08 so the signatures over
        // otherwise-identical key bytes differ.
        val pub = ByteArray(32) { 7 }
        assertTrue(WhatsAppPqPreKey.signingInput(pub)[0] != 0x05.toByte())
    }
}
