package com.vayunmathur.communicate.data.signal.registration

import android.util.Base64
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.e2e.RustSignalCrypto
import com.vayunmathur.communicate.data.signal.e2e.SignalE2E
import java.security.SecureRandom

/**
 * Generates the client key material for a fresh Signal registration.
 * Mirrors `data/whatsapp/registration/RegistrationKeys.kt` for Signal.
 *
 * Produces: identity keypair, signed pre-key, registrationId, and (best-effort)
 * Kyber PQ last-resort prekey. The scaffold is persisted as [SignalAuthData] so
 * code→verify retries reuse the same keys.
 */
class SignalRegistrationKeys private constructor(val authScaffold: SignalAuthData) {
    companion object {
        fun generate(phoneNumber: String): SignalRegistrationKeys {
            val rng = SecureRandom()
            val identity = RustSignalCrypto.generateKeyPairSplit()
            val signedPreKey = RustSignalCrypto.generateKeyPairSplit()
            val registrationId = rng.nextInt(0x3FFF) + 1
            val signedPreKeyId = 1
            val signature = SignalE2E.signSignedPreKey(identity.privateKey, signedPreKey.publicKey)
            val pq = try { SignalPqPreKey.generate(identity.privateKey, 1) } catch (_: Throwable) { null }
            val scaffold = SignalAuthData(
                phoneNumber = phoneNumber,
                aci = "",
                pni = "",
                deviceId = 1,
                identityPrivateKey = b64(identity.privateKey),
                identityPublicKey = b64(identity.publicKey),
                registrationId = registrationId,
                signedPreKeyId = signedPreKeyId,
                signedPreKeyPublic = b64(signedPreKey.publicKey),
                signedPreKeyPrivate = b64(signedPreKey.privateKey),
                signedPreKeySignature = b64(signature),
                pqLastResortKeyId = pq?.keyId ?: 0,
                pqLastResortPublic = pq?.let { b64(it.publicKey) } ?: "",
                pqLastResortSecret = pq?.let { b64(it.secretKey) } ?: "",
                pqLastResortSignature = pq?.let { b64(it.signature) } ?: "",
                kyberPreKeyId = pq?.keyId ?: 0,
                kyberPreKeyPublic = pq?.let { b64(it.publicKey) } ?: "",
                kyberPreKeySecret = pq?.let { b64(it.secretKey) } ?: "",
                kyberPreKeySignature = pq?.let { b64(it.signature) } ?: "",
                registered = false,
            )
            return SignalRegistrationKeys(scaffold)
        }
        private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    }
}
