package com.vayunmathur.e2ee

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * These exercise the real native PQC (libe2ee_pqc.so), so they only run on-device
 * / when the native lib is loadable. Byte-level interop with the previously-
 * deployed Bouncy Castle encoding is covered by the Rust crate's own tests
 * (library/e2ee-p2p/src/main/rust/src/tests.rs) against captured BC vectors.
 */
class PqcTest {
    private var nativeAvailable = false

    @Before
    fun checkNative() {
        nativeAvailable = runCatching { Pqc.generateKem() }.isSuccess
        assumeTrue("native e2ee_pqc lib not loadable on this host", nativeAvailable)
    }

    @Test
    fun ml_kem_encrypt_decrypt_roundtrip() {
        val (kemPub, kemPriv) = Pqc.generateKem()
        val (dsaPub, _) = Pqc.generateDsa()
        val bundle = Pqc.bundle(kemPub, dsaPub)
        val msg = "post-quantum hello — a longer payload than RSA-OAEP could ever hold in one shot".encodeToByteArray()
        val ct = Pqc.encryptTo(bundle, msg)
        assertArrayEquals(msg, Pqc.decrypt(kemPriv, ct))
    }

    @Test
    fun ml_dsa_sign_verify() {
        val (kemPub, _) = Pqc.generateKem()
        val (dsaPub, dsaPriv) = Pqc.generateDsa()
        val bundle = Pqc.bundle(kemPub, dsaPub)
        val data = "authenticate me".encodeToByteArray()
        val sig = Pqc.signWith(dsaPriv, data)
        assertTrue(Pqc.verify(bundle, data, sig))
        assertFalse(Pqc.verify(bundle, "tampered".encodeToByteArray(), sig))

        val (ok, od) = Pqc.generateKem().first to Pqc.generateDsa().first
        val other = Pqc.bundle(ok, od)
        assertFalse(Pqc.verify(other, data, sig))
    }

    @Test
    fun security_code_matches_both_sides() {
        val a = Pqc.bundle(Pqc.generateKem().first, Pqc.generateDsa().first)
        val b = Pqc.bundle(Pqc.generateKem().first, Pqc.generateDsa().first)
        assertTrue(Pqc.securityCode(a, b) == Pqc.securityCode(b, a)) // order-independent
    }
}
