package com.vayunmathur.passwords.cable

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.jce.ECNamedCurveTable
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.interfaces.ECPublicKey

/**
 * Verifies the platform-crypto (Conscrypt/SunEC) replacements for the former Bouncy Castle
 * code produce byte-identical results. BC stays on the *test* classpath as the oracle only.
 */
class CryptoParityTest {

    private fun bcHkdf(ikm: ByteArray, salt: ByteArray?, info: ByteArray, len: Int): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(ikm, salt, info))
        return ByteArray(len).also { gen.generateBytes(it, 0, len) }
    }

    @Test
    fun hkdf_matches_bouncycastle() {
        val ikm = ByteArray(16) { it.toByte() }
        val info = "test-info".toByteArray()
        val cases = listOf<ByteArray?>(null, ByteArray(0), "some-salt".toByteArray())
        for (salt in cases) {
            for (len in intArrayOf(10, 16, 32, 64, 100)) {
                assertArrayEquals(
                    "salt=${salt?.size} len=$len",
                    bcHkdf(ikm, salt, info, len),
                    CableKeys.hkdf(ikm, salt, info, len),
                )
            }
        }
    }

    @Test
    fun p256_compressed_decode_matches_bouncycastle() {
        val bcCurve = ECNamedCurveTable.getParameterSpec("secp256r1").curve
        repeat(20) {
            val kp = P256.generateKeyPair()
            val compressed = P256.toCompressed(kp.public)
            // Our decode.
            val mine = P256.decodePoint(compressed) as ECPublicKey
            // BC decode of the same bytes.
            val bc = bcCurve.decodePoint(compressed).normalize()
            assertEquals(bc.affineXCoord.toBigInteger(), mine.w.affineX)
            assertEquals(bc.affineYCoord.toBigInteger(), mine.w.affineY)
            // Round-trip: uncompressed decode too.
            val uncompressed = P256.toUncompressed(kp.public)
            val mine2 = P256.decodePoint(uncompressed) as ECPublicKey
            assertEquals(mine.w.affineX, mine2.w.affineX)
            assertEquals(mine.w.affineY, mine2.w.affineY)
        }
    }

    @Test
    fun p256_ecdh_is_symmetric() {
        val a = P256.generateKeyPair()
        val b = P256.generateKeyPair()
        val ab = P256.ecdh(a.private, P256.decodePoint(P256.toUncompressed(b.public)))
        val ba = P256.ecdh(b.private, P256.decodePoint(P256.toUncompressed(a.public)))
        assertArrayEquals(ab, ba)
        assertEquals(P256.DH_OUTPUT_SIZE, ab.size)
    }
}
