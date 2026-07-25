package com.vayunmathur.messages.telegram.mtproto.crypto

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-IGE (MTProto) built on the platform AES block cipher (Conscrypt,
 * `AES/ECB/NoPadding` as the raw single-block primitive) — no Bouncy Castle. The
 * IGE chaining is implemented here; ECB is used only to apply the AES permutation
 * to one 16-byte block at a time.
 */
object AesIge {
    private const val BLOCK = 16

    private fun cipher(encrypt: Boolean, key: ByteArray): Cipher =
        Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        }

    fun encrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 key must be 32 bytes" }
        require(iv.size == 32) { "IGE IV must be 32 bytes" }
        require(data.size % BLOCK == 0) { "Data must be aligned to 16 bytes" }

        val aes = cipher(encrypt = true, key = key)

        val result = ByteArray(data.size)
        val xorBuf = ByteArray(BLOCK)

        var prevCiphertext = iv.copyOfRange(0, BLOCK)
        var prevPlaintext = iv.copyOfRange(BLOCK, 32)

        for (i in data.indices step BLOCK) {
            for (j in 0 until BLOCK) {
                xorBuf[j] = (data[i + j].toInt() xor prevCiphertext[j].toInt()).toByte()
            }
            val outBlock = aes.doFinal(xorBuf)
            for (j in 0 until BLOCK) {
                result[i + j] = (outBlock[j].toInt() xor prevPlaintext[j].toInt()).toByte()
            }
            prevCiphertext = result.copyOfRange(i, i + BLOCK)
            prevPlaintext = data.copyOfRange(i, i + BLOCK)
        }
        return result
    }

    fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 key must be 32 bytes" }
        require(iv.size == 32) { "IGE IV must be 32 bytes" }
        require(data.size % BLOCK == 0) { "Data must be aligned to 16 bytes" }

        val aes = cipher(encrypt = false, key = key)

        val result = ByteArray(data.size)
        val xorBuf = ByteArray(BLOCK)

        var prevCiphertext = iv.copyOfRange(0, BLOCK)
        var prevPlaintext = iv.copyOfRange(BLOCK, 32)

        for (i in data.indices step BLOCK) {
            for (j in 0 until BLOCK) {
                xorBuf[j] = (data[i + j].toInt() xor prevPlaintext[j].toInt()).toByte()
            }
            val outBlock = aes.doFinal(xorBuf)
            for (j in 0 until BLOCK) {
                result[i + j] = (outBlock[j].toInt() xor prevCiphertext[j].toInt()).toByte()
            }
            prevPlaintext = result.copyOfRange(i, i + BLOCK)
            prevCiphertext = data.copyOfRange(i, i + BLOCK)
        }
        return result
    }
}
