package com.vayunmathur.communicate.data.signal

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json as KotlinJson

/**
 * Persistent auth data for the Signal primary client (own phone-number registration).
 *
 * Mirrors [com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData] but for Signal's
 * registration (ACI/PNI, Kyber pre-keys). Persisted under `communicate_signal_auth` so it
 * never collides with WhatsApp or other lines.
 */
@Serializable
data class SignalAuthData(
    val phoneNumber: String,
    /** Account ACI (UUID string), assigned at registration. */
    val aci: String = "",
    /** Phone-number identity PNI (UUID string). */
    val pni: String = "",
    /** Device id — primary device is always 1 on Signal. */
    val deviceId: Int = 1,
    // Signal identity key pair (Curve25519) — E2E.
    val identityPrivateKey: String = "", // Base64
    val identityPublicKey: String = "", // Base64
    // Signal Protocol registration id.
    val registrationId: Int = 0,
    // Signed pre-key.
    val signedPreKeyId: Int = 0,
    val signedPreKeyPublic: String = "", // Base64
    val signedPreKeyPrivate: String = "", // Base64
    val signedPreKeySignature: String = "", // Base64
    // Post-quantum (Kyber1024 / ML-KEM) last-resort pre-keys.
    // All-or-none: emitted together when the id is non-zero.
    val pqLastResortKeyId: Int = 0,
    val pqLastResortPublic: String = "", // Base64 (raw Kyber pub)
    val pqLastResortSecret: String = "", // Base64 (serialized KEM secret)
    val pqLastResortSignature: String = "", // Base64 (XEdDSA sig over 0x08||pub)
    val kyberPreKeyId: Int = 0,
    val kyberPreKeyPublic: String = "",
    val kyberPreKeySecret: String = "",
    val kyberPreKeySignature: String = "",
    // Whether registration completed (Signal line is live).
    val registered: Boolean = false,
    val profileName: String = "",
) {
    companion object {
        private const val PREFS_NAME = "communicate_signal_auth"
        private const val KEY_AUTH_DATA = "auth_data"

        fun load(context: Context): SignalAuthData? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_AUTH_DATA, null) ?: return null
            return try {
                KotlinJson.decodeFromString<SignalAuthData>(json)
            } catch (_: Exception) {
                null
            }
        }

        fun save(context: Context, authData: SignalAuthData) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = KotlinJson.encodeToString(authData)
            prefs.edit { putString(KEY_AUTH_DATA, json) }
        }

        fun clear(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit { remove(KEY_AUTH_DATA) }
        }
    }
}
