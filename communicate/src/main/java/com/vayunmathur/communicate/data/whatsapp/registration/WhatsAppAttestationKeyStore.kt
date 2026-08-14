package com.vayunmathur.communicate.data.whatsapp.registration

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Hardware-backed attestation key for the registration `H` HMAC (w2.md §1.4/§2.3, Phase B 2c).
 *
 * The official client keys `H = HMAC-SHA256(ENC, attestationKey)` from an AndroidKeyStore-backed
 * device key (`C1DB`). We replace the previous software `SecureRandom` key
 * ([WhatsAppDeviceFingerprint.attestationKey]) with a non-exportable **AndroidKeyStore**
 * HMAC-SHA256 key generated once, and sign via the KeyStore `Mac` handle so the raw key never
 * leaves secure hardware (StrongBox / TEE where available).
 *
 * [signWithFallback] falls back to a caller-provided software HMAC key when the KeyStore is
 * unavailable (e.g. no hardware keystore), so `H` remains best-effort exactly like the official
 * client (which warns and continues when it can't produce `H`).
 *
 * Note: AndroidKeyStore key-attestation certificate chains (`setAttestationChallenge`) are only
 * emitted for asymmetric (EC/RSA) keys, not HMAC keys, so no cert chain is captured here.
 */
object WhatsAppAttestationKeyStore {

    private const val TAG = "WAAttestKeyStore"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "communicate_whatsapp_attestation_hmac"

    /** True if a hardware/software AndroidKeyStore HMAC key could be obtained. */
    fun isAvailable(): Boolean = runCatching { getOrCreateKey() != null }.getOrDefault(false)

    /**
     * Sign [body] with the KeyStore HMAC key; on any failure sign with [softwareKey] instead.
     * Returns the raw 32-byte MAC.
     */
    fun signWithFallback(body: ByteArray, softwareKey: ByteArray): ByteArray {
        signHardware(body)?.let { return it }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(softwareKey, "HmacSHA256"))
        return mac.doFinal(body)
    }

    /** Sign [body] via the AndroidKeyStore HMAC key, or null if the KeyStore path is unavailable. */
    fun signHardware(body: ByteArray): ByteArray? = runCatching {
        val key = getOrCreateKey() ?: return null
        val mac = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256)
        mac.init(key)
        mac.doFinal(body)
    }.getOrElse {
        Log.w(TAG, "KeyStore HMAC sign failed; caller should fall back", it)
        null
    }

    private fun getOrCreateKey(): SecretKey? {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return gen.generateKey()
    }

    /** For diagnostics/tests: reads whether the generated key reports as inside secure hardware. */
    @Suppress("DEPRECATION")
    fun describe(context: Context): String = runCatching {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val key = ks.getKey(KEY_ALIAS, null) ?: return "no-key"
        val factory = javax.crypto.SecretKeyFactory.getInstance(key.algorithm, KEYSTORE)
        val info = factory.getKeySpec(key as SecretKey, android.security.keystore.KeyInfo::class.java)
            as android.security.keystore.KeyInfo
        "hardwareBacked=${info.isInsideSecureHardware}"
    }.getOrDefault("unavailable")
}
