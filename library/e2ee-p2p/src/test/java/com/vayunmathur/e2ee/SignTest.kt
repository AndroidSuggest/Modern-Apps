package com.vayunmathur.e2ee

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignTest {
    @Test
    fun sign_verify_roundtrip_reusing_identity_key() = runBlocking {
        val kp = E2ee.generateKeyPair() // OAEP-generated key, reused for PSS signatures
        val data = "the quick brown fox".encodeToByteArray()
        val sig = E2ee.sign(kp.privateKeyPem, data)
        assertTrue(E2ee.verify(kp.publicKeyPem, data, sig), "valid signature verifies")
        assertFalse(E2ee.verify(kp.publicKeyPem, "different".encodeToByteArray(), sig), "tampered data fails")

        val other = E2ee.generateKeyPair()
        assertFalse(E2ee.verify(other.publicKeyPem, data, sig), "wrong key fails")
    }
}
