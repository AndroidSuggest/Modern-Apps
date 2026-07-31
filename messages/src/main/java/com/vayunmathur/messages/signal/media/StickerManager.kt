package com.vayunmathur.messages.signal.media

import android.util.Log
import com.vayunmathur.messages.signal.web.SignalHttpClient
import com.vayunmathur.messages.signal.proto.StickerProtos
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object StickerManager {

    private const val TAG = "StickerManager"

    suspend fun downloadStickerPackManifest(packId: ByteArray, packKey: ByteArray): StickerProtos.Pack? {
        if (packId.size != 16) return null
        val hexId = packId.joinToString("") { "%02x".format(it) }
        val decrypted = downloadStickerData("/stickers/$hexId/manifest.proto", packKey) ?: return null
        return try {
            StickerProtos.Pack.parseFrom(decrypted)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse sticker pack manifest", e)
            null
        }
    }

    suspend fun downloadStickerPackItem(packId: ByteArray, packKey: ByteArray, stickerId: Int): ByteArray? {
        if (packId.size != 16) return null
        val hexId = packId.joinToString("") { "%02x".format(it) }
        return downloadStickerData("/stickers/$hexId/full/$stickerId", packKey)
    }

    private suspend fun downloadStickerData(path: String, packKey: ByteArray): ByteArray? {
        if (packKey.size != 32) return null
        val response = SignalHttpClient.request(
            host = SignalHttpClient.CDN1_HOST,
            method = "GET",
            path = path,
        )
        if (response.status != 200) {
            Log.e(TAG, "Sticker download failed: ${response.status}")
            return null
        }
        val body = response.bytes
        return decryptSticker(packKey, body)
    }

    private fun decryptSticker(packKey: ByteArray, ciphertext: ByteArray): ByteArray {
        val derivedKey = deriveStickerPackKey(packKey)
        return macAndAESDecrypt(ciphertext, derivedKey)
    }

    private fun deriveStickerPackKey(key: ByteArray): ByteArray {
        val salt = ByteArray(32)
        return hkdfExpand(key, salt, "Sticker Pack".toByteArray(), 2 * 32)
    }

    fun hkdfExpand(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(salt, "HmacSHA256"))
            doFinal(ikm)
        }
        val result = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter: Byte = 1
        while (offset < length) {
            val hmac = Mac.getInstance("HmacSHA256")
            hmac.init(SecretKeySpec(prk, "HmacSHA256"))
            hmac.update(t)
            hmac.update(info)
            hmac.update(byteArrayOf(counter))
            t = hmac.doFinal()
            val toCopy = minOf(t.size, length - offset)
            t.copyInto(result, offset, 0, toCopy)
            offset += toCopy
            counter++
        }
        return result
    }

    fun macAndAESDecrypt(ciphertext: ByteArray, keys: ByteArray): ByteArray {
        val aesKey = keys.copyOfRange(0, 32)
        val macKey = keys.copyOfRange(32, 64)
        val iv = ciphertext.copyOfRange(0, 16)
        val encrypted = ciphertext.copyOfRange(16, ciphertext.size - 32)
        val theirMac = ciphertext.copyOfRange(ciphertext.size - 32, ciphertext.size)

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(macKey, "HmacSHA256"))
        mac.update(iv)
        mac.update(encrypted)
        val ourMac = mac.doFinal()
        require(MessageDigest.isEqual(ourMac, theirMac)) { "MAC verification failed" }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(encrypted)
    }
}
