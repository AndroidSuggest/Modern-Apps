package com.vayunmathur.messages.whatsapp.e2e

import android.util.Log

/**
 * JNI wrapper around `libwhatsapp_signal.so` (Rust crate `whatsapp_signal`).
 * All methods are pure — Kotlin owns Room persistence and passes opaque blobs.
 *
 * The nativelib is loaded once; [isAvailable] is false when the `.so` is missing
 * (e.g. on unsupported ABI) so callers can degrade gracefully.
 */
object RustWhatsAppCrypto {

    private const val TAG = "RustWhatsAppCrypto"

    val isAvailable: Boolean = try {
        System.loadLibrary("whatsapp_signal")
        Log.i(TAG, "libwhatsapp_signal loaded")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "System.loadLibrary(whatsapp_signal) failed", t)
        false
    }

    // -- Primitive key operations --

    /**
     * Generate a fresh X25519 key pair.
     * @return 64 bytes: private(32) || public(32), or null.
     */
    @JvmStatic
    external fun generateKeyPair(): ByteArray?

    @JvmStatic
    external fun publicFromPrivate(privateKey: ByteArray): ByteArray?

    @JvmStatic
    external fun x25519Agreement(privateKey: ByteArray, publicKey: ByteArray): ByteArray?

    @JvmStatic
    external fun sign(privateKey: ByteArray, message: ByteArray): ByteArray?

    @JvmStatic
    external fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean

    // -- Session --

    /**
     * X3DH initiator: process a peer bundle.
     *
     * @param preKeyId -1 if no one-time prekey, else its id.
     * @param preKeyPublic null / empty when absent, else 32 bytes.
     * @return serialized SessionState bytes (Rust record version 1), or null on error.
     */
    @JvmStatic
    external fun processPreKeyBundle(
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

    /**
     * Encrypt with an existing session.
     * @return Array[3] = [isPreKey(1 byte 0/1), ciphertextBody, newSessionBytes] or null.
     */
    @JvmStatic
    external fun encrypt(sessionBytes: ByteArray, plaintext: ByteArray): Array<ByteArray>?

    /**
     * Decrypt a normal `msg`.
     * @return Array[2] = [plaintext, newSessionBytes] or null.
     */
    @JvmStatic
    external fun decryptMessage(sessionBytes: ByteArray, ciphertext: ByteArray): Array<ByteArray>?

    /**
     * Decrypt an inbound `pkmsg`: X3DH (Bob) + first Double-Ratchet message.
     * @param oneTimePrivate null / empty if the bundle had no one-time prekey.
     * @return Array[2] = [plaintext, newSessionBytes] or null.
     */
    @JvmStatic
    external fun decryptPreKeyMessage(
        localIdentityPrivate: ByteArray,
        localIdentityPublic: ByteArray,
        signedPreKeyPrivate: ByteArray,
        oneTimePrivate: ByteArray?,
        preKeyMessageBytes: ByteArray,
    ): Array<ByteArray>?

    // -- Group (Sender Keys) --

    /** @return Array[2] = [stateBytes, skdmBytes] */
    @JvmStatic
    external fun createSenderKey(): Array<ByteArray>?

    @JvmStatic
    external fun processSenderKey(skdmBytes: ByteArray): ByteArray?

    /** @return Array[2] = [ciphertext, newStateBytes] */
    @JvmStatic
    external fun encryptGroup(stateBytes: ByteArray, plaintext: ByteArray): Array<ByteArray>?

    /** @return Array[2] = [plaintext, newStateBytes] */
    @JvmStatic
    external fun decryptGroup(stateBytes: ByteArray, ciphertext: ByteArray): Array<ByteArray>?

    // -- Convenience helpers used by callers --

    data class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun generateKeyPairSplit(): KeyPair {
        val blob = generateKeyPair() ?: throw RuntimeException("Rust generateKeyPair returned null")
        if (blob.size != 64) throw RuntimeException("generateKeyPair expected 64 bytes, got ${blob.size}")
        return KeyPair(
            privateKey = blob.copyOfRange(0, 32),
            publicKey = blob.copyOfRange(32, 64),
        )
    }

    data class EncryptResult(val isPreKey: Boolean, val body: ByteArray, val newSession: ByteArray)
    fun encryptSplit(sessionBytes: ByteArray, plaintext: ByteArray): EncryptResult {
        val out = encrypt(sessionBytes, plaintext) ?: throw RuntimeException("Rust encrypt returned null")
        if (out.size != 3) throw RuntimeException("encrypt expected 3 parts, got ${out.size}")
        return EncryptResult(
            isPreKey = out[0].isNotEmpty() && out[0][0].toInt() != 0,
            body = out[1],
            newSession = out[2],
        )
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
        val out = decryptPreKeyMessage(
            localIdentityPrivate, localIdentityPublic, signedPreKeyPrivate,
            oneTimePrivate, preKeyMessageBytes,
        ) ?: throw RuntimeException("Rust decryptPreKeyMessage null")
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
