package com.vayunmathur.communicate.data.signal.registration

import android.util.Base64
import android.util.Log
import com.vayunmathur.communicate.data.signal.e2e.RustSignalCrypto
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Signal registration attestation helpers.
 * Mirrors `data/whatsapp/registration/RegistrationAttestation.kt` for Signal.
 *
 * Signal does not use WhatsApp's token asset flow; instead this file provides
 * the ENC wrapper (optional) and HMAC signing for registration bodies when needed.
 */
object RegistrationAttestation {
    private const val TAG = "SignalRegAttestation"

    fun encryptQueryString(queryString: String, serverPubHex: String): String? {
        return try {
            val serverPub = hexToBytes(serverPubHex)
            val eph = RustSignalCrypto.generateKeyPairSplit()
            val shared = RustSignalCrypto.x25519Agreement(eph.privateKey, serverPub) ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(shared, "AES"), GCMParameterSpec(128, ByteArray(12)))
            val ct = cipher.doFinal(queryString.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(eph.publicKey + ct, Base64.NO_WRAP)
        } catch (t: Throwable) {
            Log.w(TAG, "encryptQueryString failed", t)
            null
        }
    }

    fun signWithAttestation(body: String, key: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return Base64.encodeToString(mac.doFinal(body.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) out[i] = ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
        return out
    }
}
