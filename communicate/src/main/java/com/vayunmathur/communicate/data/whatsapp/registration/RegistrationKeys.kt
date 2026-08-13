package com.vayunmathur.communicate.data.whatsapp.registration

import android.util.Base64
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.e2e.RustWhatsAppCrypto
import com.vayunmathur.communicate.data.whatsapp.e2e.WhatsAppE2E
import java.security.SecureRandom

/**
 * Generates and holds the client key material for a fresh primary registration: the Signal identity
 * keypair, the Noise-static keypair, and a signed pre-key, plus `registrationId`/`signedPreKeyId`.
 * Produces a [WhatsAppAuthData] scaffold to persist and the E2E-bundle wire fields.
 *
 * The E2E bundle fields (`authkey`, `e_ident`, `e_keytype`, `e_regid`, `e_skey_id`, `e_skey_val`,
 * `e_skey_sig`) are **URL-safe base64, no padding** — matching the APK's `C34040Euf`→`A04`→`DIj.A0w`
 * (flag 11). AuthData stores the same keys as STANDARD base64 for internal use, so [bundleFields]
 * decodes and re-encodes to URL-safe base64 for the wire.
 */
class RegistrationKeys private constructor(
    val authScaffold: WhatsAppAuthData,
) {
    /** URL-safe-base64 E2E bundle fields for `/v2/ endpoints` (from the freshly-generated scaffold). */
    fun registerBundleFields(): Map<String, String> = bundleFields(authScaffold)

    companion object {
        fun generate(phoneNumber: String): RegistrationKeys {
            val rng = SecureRandom()

            val identity = RustWhatsAppCrypto.generateKeyPairSplit()
            val noise = RustWhatsAppCrypto.generateKeyPairSplit()
            val signedPreKey = RustWhatsAppCrypto.generateKeyPairSplit()

            val registrationId = rng.nextInt(0x3FFF) + 1
            val signedPreKeyId = 1

            val signature = WhatsAppE2E.signSignedPreKey(identity.privateKey, signedPreKey.publicKey)

            val scaffold = WhatsAppAuthData(
                phoneNumber = phoneNumber,
                pushName = "",
                wid = "", // filled from `new_jid` after /v2/register
                noisePrivateKey = b64(noise.privateKey),
                noisePublicKey = b64(noise.publicKey),
                identityPrivateKey = b64(identity.privateKey),
                identityPublicKey = b64(identity.publicKey),
                registrationId = registrationId,
                signedPreKeyId = signedPreKeyId,
                signedPreKeyPublic = b64(signedPreKey.publicKey),
                signedPreKeyPrivate = b64(signedPreKey.privateKey),
                signedPreKeySignature = b64(signature),
                deviceId = 0,
            )
            return RegistrationKeys(scaffold)
        }

        private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
        private fun dec(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

        /**
         * The `/v2/ endpoints` E2E key bundle, URL-safe-base64 (A04), rebuilt from persisted [WhatsAppAuthData]
         * so the same values are reused across the code→register calls.
         */
        fun bundleFields(auth: WhatsAppAuthData): Map<String, String> = mapOf(
            "e_regid" to RegEncoding.b64Url(intBe(auth.registrationId, 4)),
            "e_keytype" to RegEncoding.b64Url(byteArrayOf(WhatsAppRegistrationConstants.KEY_TYPE_CURVE25519)),
            "e_ident" to RegEncoding.b64Url(dec(auth.identityPublicKey)),
            "e_skey_id" to RegEncoding.b64Url(intBe(auth.signedPreKeyId, 3)),
            "e_skey_val" to RegEncoding.b64Url(dec(auth.signedPreKeyPublic)),
            "e_skey_sig" to RegEncoding.b64Url(dec(auth.signedPreKeySignature)),
            "authkey" to RegEncoding.b64Url(dec(auth.noisePublicKey)),
        )

        /** Big-endian encode [value] into [len] bytes (len ≤ 4). */
        private fun intBe(value: Int, len: Int): ByteArray {
            val out = ByteArray(len)
            for (i in 0 until len) {
                out[len - 1 - i] = (value ushr (8 * i)).toByte()
            }
            return out
        }
    }
}
