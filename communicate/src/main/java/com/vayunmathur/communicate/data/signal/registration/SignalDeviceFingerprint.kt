package com.vayunmathur.communicate.data.signal.registration

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import java.security.SecureRandom
import java.util.UUID

/**
 * Stable per-install identifiers for Signal registration.
 * Mirrors `data/whatsapp/registration/WhatsAppDeviceFingerprint.kt` for Signal.
 *
 * Signal does not use WhatsApp's expid/recoveryToken attestation, but we keep the same
 * shape so the registration client has a stable install id across retries.
 */
class SignalDeviceFingerprint private constructor(
    val fdid: String,
    val installId: String,
    val recoveryToken: ByteArray,
    val attestationKey: ByteArray,
) {
    companion object {
        private const val PREFS = "communicate_signal_fingerprint"
        private const val K_FDID = "fdid"
        private const val K_INSTALL = "install_id"
        private const val K_ID = "recovery_id"
        private const val K_ATTEST = "attest_key"

        fun getOrCreate(context: Context): SignalDeviceFingerprint {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val rng = SecureRandom()
            fun bytes(key: String, len: Int): ByteArray {
                val existing = prefs.getString(key, null)
                if (existing != null) return Base64.decode(existing, Base64.NO_WRAP)
                val b = ByteArray(len).also { rng.nextBytes(it) }
                prefs.edit { putString(key, Base64.encodeToString(b, Base64.NO_WRAP)) }
                return b
            }
            fun uuid(key: String): String =
                prefs.getString(key, null) ?: UUID.randomUUID().toString().also {
                    prefs.edit { putString(key, it) }
                }
            return SignalDeviceFingerprint(
                fdid = uuid(K_FDID),
                installId = uuid(K_INSTALL),
                recoveryToken = bytes(K_ID, 16),
                attestationKey = bytes(K_ATTEST, 32),
            )
        }

        fun clear(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
        }
    }
}
