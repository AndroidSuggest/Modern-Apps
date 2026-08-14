package com.vayunmathur.communicate.data.signal.registration

import com.vayunmathur.communicate.data.signal.e2e.RustSignalCrypto
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType

/**
 * Post-quantum (Kyber1024 / ML-KEM) last-resort prekey for Signal registration.
 * Mirrors `data/whatsapp/registration/WhatsAppPqPreKey.kt` for Signal.
 *
 * Signal's Kyber pre-keys use the same KEM pair generation; the signature is XEdDSA
 * over `0x08 || pqPublicKey` using the identity private key.
 */
object SignalPqPreKey {

    data class Generated(
        val keyId: Int,
        val publicKey: ByteArray,
        val secretKey: ByteArray,
        val signature: ByteArray,
    )

    fun signingInput(pqPublicKey: ByteArray): ByteArray {
        val out = ByteArray(1 + pqPublicKey.size)
        out[0] = 0x08
        System.arraycopy(pqPublicKey, 0, out, 1, pqPublicKey.size)
        return out
    }

    private fun stripTypeTag(serialized: ByteArray): ByteArray =
        if (serialized.size == 1568) serialized else serialized.copyOfRange(1, serialized.size)

    fun generate(identityPrivate32: ByteArray, keyId: Int): Generated {
        val kp = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val pub = stripTypeTag(kp.publicKey.serialize())
        val secret = kp.secretKey.serialize()
        val signature = RustSignalCrypto.sign(identityPrivate32, signingInput(pub))
            ?: throw RuntimeException("Rust sign returned null for PQ prekey")
        return Generated(keyId = keyId, publicKey = pub, secretKey = secret, signature = signature)
    }
}
