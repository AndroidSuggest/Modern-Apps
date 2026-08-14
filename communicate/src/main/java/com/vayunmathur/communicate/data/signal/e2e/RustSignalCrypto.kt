package com.vayunmathur.communicate.data.signal.e2e

import android.util.Log

/**
 * JNI wrapper around `libcommunicate_signal.so` (Rust crate `communicate_signal`) for the
 * **Signal** primary client.
 *
 * Reuses the same `.so` as [com.vayunmathur.communicate.data.whatsapp.e2e.RustWhatsAppCrypto];
 * no second `rustNativeLib` is added. The Rust crate exposes both WhatsApp and Signal symbols
 * from the single cdylib (see `src/main/rust/src/jni_bridge.rs` + `signal.rs`).
 *
 * All methods are pure — Kotlin owns Room and passes opaque session/sender-key blobs.
 */
object RustSignalCrypto {

    private const val TAG = "RustSignalCrypto"

    val isAvailable: Boolean = try {
        System.loadLibrary("communicate_signal")
        Log.i(TAG, "libcommunicate_signal loaded (Signal)")
        true
    } catch (t: Throwable) {
        // Already loaded by RustWhatsAppCrypto is OK — UnsatisfiedLinkError with "already loaded" is not fatal
        if (t.message?.contains("already loaded", ignoreCase = true) == true) {
            Log.i(TAG, "libcommunicate_signal already loaded")
            true
        } else {
            Log.e(TAG, "System.loadLibrary(communicate_signal) failed", t)
            false
        }
    }

    // -- Primitive key operations (reused from crypto.rs) --

    @JvmStatic external fun generateKeyPair(): ByteArray?
    @JvmStatic external fun publicFromPrivate(privateKey: ByteArray): ByteArray?
    @JvmStatic external fun x25519Agreement(privateKey: ByteArray, publicKey: ByteArray): ByteArray?
    @JvmStatic external fun sign(privateKey: ByteArray, message: ByteArray): ByteArray?
    @JvmStatic external fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean

    // -- Session (same wire as WhatsApp, different JNI class) --

    @JvmStatic external fun processPreKeyBundle(
        localIdentityPrivate: ByteArray,
        localIdentityPublic: ByteArray,
        localRegistrationId: Int,
        registrationId: Int,
        preKeyId: Int,
        preKeyPublic: ByteArray?,
        signedPreKeyId: Int,
        signedPreKeyPublic: ByteArray,
        signedPreKeySignature: ByteArray,
        identityKey: ByteArray,
    ): ByteArray?

    @JvmStatic external fun encrypt(sessionBytes: ByteArray, plaintext: ByteArray): Array<ByteArray>?
    @JvmStatic external fun decryptMessage(sessionBytes: ByteArray, ciphertext: ByteArray): Array<ByteArray>?
    @JvmStatic external fun decryptPreKeyMessage(
        localIdentityPrivate: ByteArray,
        localIdentityPublic: ByteArray,
        signedPreKeyPrivate: ByteArray,
        oneTimePrivate: ByteArray?,
        preKeyMessageBytes: ByteArray,
    ): Array<ByteArray>?

    // -- Group (Sender Keys) --

    @JvmStatic external fun createSenderKey(): Array<ByteArray>?
    @JvmStatic external fun processSenderKey(skdmBytes: ByteArray): ByteArray?
    @JvmStatic external fun encryptGroup(stateBytes: ByteArray, plaintext: ByteArray): Array<ByteArray>?
    @JvmStatic external fun decryptGroup(stateBytes: ByteArray, ciphertext: ByteArray): Array<ByteArray>?

    // -- Sealed sender (Signal-specific, in signal.rs) --

    /** Sealed-sender encrypt: returns ciphertext bytes or null. */
    @JvmStatic external fun sealedSenderEncrypt(plaintext: ByteArray, recipientAci: String, recipientDeviceId: Int): ByteArray?
    /** Sealed-sender decrypt: returns plaintext bytes or null. */
    @JvmStatic external fun sealedSenderDecrypt(ciphertext: ByteArray): ByteArray?

    // -- Convenience helpers --

    data class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun generateKeyPairSplit(): KeyPair {
        val blob = generateKeyPair() ?: throw RuntimeException("Rust generateKeyPair returned null")
        if (blob.size != 64) throw RuntimeException("generateKeyPair expected 64 bytes, got ${blob.size}")
        return KeyPair(blob.copyOfRange(0, 32), blob.copyOfRange(32, 64))
    }

    data class EncryptResult(val isPreKey: Boolean, val body: ByteArray, val newSession: ByteArray)
    fun encryptSplit(sessionBytes: ByteArray, plaintext: ByteArray): EncryptResult {
        val out = encrypt(sessionBytes, plaintext) ?: throw RuntimeException("Rust encrypt returned null")
        if (out.size != 3) throw RuntimeException("encrypt expected 3 parts, got ${out.size}")
        return EncryptResult(out[0].isNotEmpty() && out[0][0].toInt() != 0, out[1], out[2])
    }

    data class DecryptResult(val plaintext: ByteArray, val newSession: ByteArray)
    fun decryptMessageSplit(sessionBytes: ByteArray, ciphertext: ByteArray): DecryptResult {
        val out = decryptMessage(sessionBytes, ciphertext) ?: throw RuntimeException("Rust decryptMessage null")
        if (out.size != 2) throw RuntimeException("decryptMessage expected 2 parts")
        return DecryptResult(out[0], out[1])
    }

    fun decryptPreKeySplit(
        localIdentityPrivate: ByteArray,
        localIdentityPublic: ByteArray,
        signedPreKeyPrivate: ByteArray,
        oneTimePrivate: ByteArray?,
        preKeyMessageBytes: ByteArray,
    ): DecryptResult {
        val out = decryptPreKeyMessage(localIdentityPrivate, localIdentityPublic, signedPreKeyPrivate, oneTimePrivate, preKeyMessageBytes)
            ?: throw RuntimeException("Rust decryptPreKeyMessage null")
        if (out.size != 2) throw RuntimeException("decryptPreKey expected 2 parts")
        return DecryptResult(out[0], out[1])
    }

    data class SenderKeyCreateResult(val state: ByteArray, val skdm: ByteArray)
    fun createSenderKeySplit(): SenderKeyCreateResult {
        val out = createSenderKey() ?: throw RuntimeException("Rust createSenderKey null")
        if (out.size != 2) throw RuntimeException("createSenderKey expected 2 parts")
        return SenderKeyCreateResult(out[0], out[1])
    }

    data class GroupCipherResult(val data: ByteArray, val newState: ByteArray)
    fun encryptGroupSplit(stateBytes: ByteArray, plaintext: ByteArray): GroupCipherResult {
        val out = encryptGroup(stateBytes, plaintext) ?: throw RuntimeException("Rust encryptGroup null")
        if (out.size != 2) throw RuntimeException("encryptGroup expected 2 parts")
        return GroupCipherResult(out[0], out[1])
    }

    fun decryptGroupSplit(stateBytes: ByteArray, ciphertext: ByteArray): GroupCipherResult {
        val out = decryptGroup(stateBytes, ciphertext) ?: throw RuntimeException("Rust decryptGroup null")
        if (out.size != 2) throw RuntimeException("decryptGroup expected 2 parts")
        return GroupCipherResult(out[0], out[1])
    }
}
