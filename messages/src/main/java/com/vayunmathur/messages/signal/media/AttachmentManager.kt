package com.vayunmathur.messages.signal.media

import android.util.Log
import com.vayunmathur.messages.signal.proto.SignalServiceProtos
import com.vayunmathur.messages.signal.web.SignalHttpClient
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object AttachmentManager {

    private const val TAG = "AttachmentManager"
    private const val MAC_LENGTH = 32
    private const val IV_LENGTH = 16

    class InvalidMACException : Exception("Invalid MAC for attachment")
    class InvalidDigestException(msg: String = "Invalid digest for attachment") : Exception(msg)
    class AttachmentNotFoundException : Exception("Attachment not found on server")

    suspend fun download(
        cdnId: Long,
        cdnKey: String,
        cdnNumber: Int,
        key: ByteArray,
        digest: ByteArray?,
        plaintextDigest: Boolean = false,
        size: Int,
    ): ByteArray? {
        return try {
            val path = if (cdnId != 0L) "/attachments/$cdnId" else "/attachments/$cdnKey"
            val host = SignalHttpClient.cdnHost(cdnNumber)
            val response = SignalHttpClient.request(
                host = host,
                method = "GET",
                path = path,
            )
            if (response.status == 404) throw AttachmentNotFoundException()
            if (!response.isSuccess) return null
            val body = response.bytes

            if (!plaintextDigest) {
                if (digest == null) {
                    throw InvalidDigestException("Missing digest for attachment")
                }
                val hash = MessageDigest.getInstance("SHA-256").digest(body)
                if (!MessageDigest.isEqual(hash, digest)) {
                    throw InvalidDigestException()
                }
            }

            val decrypted = macAndAESDecrypt(body, key)
            if (decrypted.size < size) {
                throw Exception("Decrypted attachment length ${decrypted.size} < expected $size")
            }
            val result = decrypted.copyOfRange(0, size)
            if (digest != null && plaintextDigest) {
                val hash = MessageDigest.getInstance("SHA-256").digest(result)
                if (!MessageDigest.isEqual(hash, digest)) {
                    throw InvalidDigestException("Invalid digest for attachment (plaintext hash)")
                }
            }
            result
        } catch (e: InvalidMACException) {
            Log.e(TAG, "MAC validation failed for attachment", e)
            throw e
        } catch (e: InvalidDigestException) {
            Log.e(TAG, "Digest validation failed for attachment", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download attachment", e)
            null
        }
    }

    suspend fun downloadWithPointer(
        pointer: SignalServiceProtos.AttachmentPointer,
        plaintextHash: ByteArray? = null,
    ): ByteArray? {
        var digest = if (pointer.hasDigest()) pointer.digest.toByteArray() else null
        var isPlaintextDigest = false
        if (digest == null && plaintextHash != null) {
            digest = plaintextHash
            isPlaintextDigest = true
        }
        return download(
            cdnId = pointer.cdnId,
            cdnKey = pointer.cdnKey,
            cdnNumber = pointer.cdnNumber,
            key = pointer.key.toByteArray(),
            digest = digest,
            plaintextDigest = isPlaintextDigest,
            size = pointer.size,
        )
    }

    suspend fun upload(
        ws: com.vayunmathur.messages.signal.web.SignalWebSocket,
        data: ByteArray,
        contentType: String,
        fileName: String?,
    ): SignalServiceProtos.AttachmentPointer? {
        return try {
            val random = SecureRandom()
            val aesKey = ByteArray(32).also { random.nextBytes(it) }
            val macKey = ByteArray(32).also { random.nextBytes(it) }
            val iv = ByteArray(16).also { random.nextBytes(it) }

            val paddedLen = padAttachmentSize(data.size)
            val paddedData = ByteArray(paddedLen)
            data.copyInto(paddedData)

            val encrypted = encryptAttachment(paddedData, aesKey, macKey, iv)
            val digest = MessageDigest.getInstance("SHA-256").digest(encrypted)

            val formResponse = ws.sendRequest(
                "GET",
                "/v4/attachments/form/upload",
            )
            if (formResponse.status !in 200..299) return null

            val formJson = JSONObject(formResponse.body.toStringUtf8())
            val uploadUrl = formJson.getString("signedUploadLocation")
            val cdnNumber = formJson.optInt("cdn", 0)
            val cdnKey = formJson.optString("key", "")
            val headers = formJson.optJSONObject("headers")

            if (cdnNumber == 3) {
                uploadAttachmentTUS(uploadUrl, cdnKey, encrypted, headers)
            } else {
                uploadAttachmentLegacy(uploadUrl, encrypted, contentType, headers)
            }

            SignalServiceProtos.AttachmentPointer.newBuilder()
                .setCdnKey(cdnKey)
                .setCdnNumber(cdnNumber)
                .setKey(com.google.protobuf.ByteString.copyFrom(aesKey + macKey))
                .setDigest(com.google.protobuf.ByteString.copyFrom(digest))
                .setSize(data.size)
                .setContentType(contentType)
                .apply { if (fileName != null) setFileName(fileName) }
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload attachment", e)
            null
        }
    }

    private fun encryptAttachment(data: ByteArray, aesKey: ByteArray, macKey: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(aesKey, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val ciphertext = cipher.doFinal(data)

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(macKey, "HmacSHA256"))
        mac.update(iv)
        mac.update(ciphertext)
        val hmac = mac.doFinal()

        return iv + ciphertext + hmac
    }

    fun macAndAESDecrypt(body: ByteArray, key: ByteArray): ByteArray {
        val l = body.size - MAC_LENGTH
        val macKey = key.copyOfRange(MAC_LENGTH, key.size)
        val aesKey = key.copyOfRange(0, MAC_LENGTH)

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(macKey, "HmacSHA256"))
        mac.update(body, 0, l)
        val computedMac = mac.doFinal()
        val expectedMac = body.copyOfRange(l, body.size)
        if (!MessageDigest.isEqual(computedMac, expectedMac)) {
            throw InvalidMACException()
        }

        val iv = body.copyOfRange(0, IV_LENGTH)
        val ciphertext = body.copyOfRange(IV_LENGTH, l)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }

    private suspend fun uploadAttachmentLegacy(
        uploadUrl: String,
        encrypted: ByteArray,
        contentType: String,
        headers: JSONObject?,
    ) {
        val headerMap = mutableMapOf<String, String>()
        headers?.keys()?.forEach { k -> headerMap[k] = headers.getString(k) }
        val allocResponse = SignalHttpClient.request(
            method = "POST",
            path = "",
            overrideUrl = uploadUrl,
            contentType = "application/octet-stream",
            headers = headerMap,
        )
        if (!allocResponse.isSuccess) throw Exception("Allocate request returned ${allocResponse.status}")
        val location = allocResponse.header("Location") ?: throw Exception("No Location header in allocate response")

        val uploadResponse = SignalHttpClient.request(
            method = "PUT",
            path = "",
            overrideUrl = location,
            body = encrypted,
            contentType = "application/octet-stream",
        )
        if (!uploadResponse.isSuccess) throw Exception("Upload request returned ${uploadResponse.status}")
    }

    private suspend fun uploadAttachmentTUS(
        uploadUrl: String,
        key: String,
        encrypted: ByteArray,
        headers: JSONObject?,
    ) {
        val tusHeaders = mutableMapOf<String, String>()
        headers?.keys()?.forEach { k -> tusHeaders[k] = headers.getString(k) }
        tusHeaders["Tus-Resumable"] = "1.0.0"
        tusHeaders["Upload-Length"] = encrypted.size.toString()
        tusHeaders["Upload-Metadata"] = "filename " + android.util.Base64.encodeToString(key.toByteArray(), android.util.Base64.NO_WRAP)

        val uploadResponse = SignalHttpClient.request(
            method = "POST",
            path = "",
            overrideUrl = uploadUrl,
            body = encrypted,
            contentType = "application/offset+octet-stream",
            headers = tusHeaders,
        )
        if (!uploadResponse.isSuccess) throw Exception("TUS upload request returned ${uploadResponse.status}")
    }

    private fun padAttachmentSize(size: Int): Int {
        if (size <= 541) return 541
        val logSize = Math.log(size.toDouble()) / Math.log(1.05)
        val rounded = Math.ceil(logSize)
        val padded = Math.floor(Math.pow(1.05, rounded)).toInt()
        if (padded < size) {
            throw IllegalStateException("Math error: padded length $padded is less than body length $size")
        }
        return padded
    }
}
