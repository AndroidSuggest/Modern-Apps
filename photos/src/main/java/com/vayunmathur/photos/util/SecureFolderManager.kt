package com.vayunmathur.photos.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import com.vayunmathur.photos.data.VaultPhoto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SecureFolderManager(val context: Context) {
    private val secureFolder = File(context.filesDir, "secure_vault")

    init {
        if (!secureFolder.exists()) secureFolder.mkdirs()
    }

    private fun getSecretKey(password: String): SecretKey {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(password.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun getCipher(mode: Int, key: SecretKey, iv: ByteArray? = null): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (iv != null) {
            cipher.init(mode, key, GCMParameterSpec(128, iv))
        } else {
            cipher.init(mode, key)
        }
        return cipher
    }

    // Chunked streaming encryption to avoid OOM on large files.
    // AES/GCM via CipherOutputStream in Conscrypt buffers the entire plaintext
    // until doFinal() (OpenSSLAeadCipher.appendToBuf), causing OutOfMemoryError
    // for +100MB files. We split into 1MiB chunks, each encrypted independently
    // with a fresh random IV and written as: [MAGIC(4)][IV(12)][len(4)][ciphertext].
    companion object {
        private const val GCM_IV_LENGTH = 12
        private const val CHUNK_SIZE = 1024 * 1024 // 1 MiB
        private val MAGIC_SEC2 = byteArrayOf(0x53, 0x45, 0x43, 0x32) // "SEC2" = Secure v2 chunked
    }

    private fun InputStream.readFully(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Boolean {
        var remaining = length
        var off = offset
        while (remaining > 0) {
            val r = read(buffer, off, remaining)
            if (r == -1) return false
            remaining -= r
            off += r
        }
        return true
    }

    private fun encryptStreamChunked(input: InputStream, output: OutputStream, key: SecretKey) {
        output.write(MAGIC_SEC2)
        val buffer = ByteArray(CHUNK_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            if (read == 0) continue
            val cipher = getCipher(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv // 12 bytes random
            val encrypted = cipher.doFinal(buffer, 0, read)
            output.write(iv)
            output.write((encrypted.size ushr 24) and 0xFF)
            output.write((encrypted.size ushr 16) and 0xFF)
            output.write((encrypted.size ushr 8) and 0xFF)
            output.write(encrypted.size and 0xFF)
            output.write(encrypted)
        }
    }

    /**
     * Decrypts both new chunked format (SEC2) and legacy single-IV format.
     * Returns false on malformed / auth failure.
     */
    private fun decryptToOutputStream(input: InputStream, output: OutputStream, key: SecretKey): Boolean {
        val first4 = ByteArray(4)
        if (!input.readFully(first4)) return false

        val isChunked = first4.contentEquals(MAGIC_SEC2)
        if (isChunked) {
            val lenBytes = ByteArray(4)
            while (true) {
                // Detect clean EOF
                val firstIvByte = input.read()
                if (firstIvByte == -1) break // done
                val iv = ByteArray(GCM_IV_LENGTH)
                iv[0] = firstIvByte.toByte()
                if (!input.readFully(iv, 1, GCM_IV_LENGTH - 1)) return false

                if (!input.readFully(lenBytes)) return false
                val encLen = ((lenBytes[0].toInt() and 0xFF) shl 24) or
                        ((lenBytes[1].toInt() and 0xFF) shl 16) or
                        ((lenBytes[2].toInt() and 0xFF) shl 8) or
                        (lenBytes[3].toInt() and 0xFF)
                if (encLen <= 0) return false

                val encData = ByteArray(encLen)
                if (!input.readFully(encData)) return false

                try {
                    val cipher = getCipher(Cipher.DECRYPT_MODE, key, iv)
                    val decrypted = cipher.doFinal(encData)
                    output.write(decrypted)
                } catch (e: Exception) {
                    return false
                }
            }
            return true
        } else {
            // Legacy format: [12 byte IV][ciphertext+tag] single GCM encryption
            val iv = ByteArray(GCM_IV_LENGTH)
            System.arraycopy(first4, 0, iv, 0, 4)
            if (!input.readFully(iv, 4, 8)) return false
            return try {
                val cipher = getCipher(Cipher.DECRYPT_MODE, key, iv)
                val encrypted = input.readBytes()
                val decrypted = cipher.doFinal(encrypted)
                output.write(decrypted)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    fun encryptAndMove(uri: Uri, name: String, password: String): Pair<String, String> {
        val key = getSecretKey(password)
        val timestamp = System.currentTimeMillis()
        val fileName = "${timestamp}_${name}.enc"
        val thumbName = "${timestamp}_${name}_thumb.enc"

        val outputFile = File(secureFolder, fileName)
        val thumbFile = File(secureFolder, thumbName)

        try {
            // 1. Generate Thumbnail
            val bitmap = context.contentResolver.loadThumbnail(uri, Size(512, 512), null)

            // 2. Encrypt Thumbnail (small, but also use chunked format for consistency)
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            bitmap.recycle()
            val thumbBytes = baos.toByteArray()

            FileOutputStream(thumbFile).use { fos ->
                ByteArrayInputStream(thumbBytes).use { input ->
                    encryptStreamChunked(input, fos, key)
                }
            }

            // 3. Encrypt Main File with streaming chunks to avoid OOM on large videos
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outputFile).use { fos ->
                    encryptStreamChunked(input, fos, key)
                }
            } ?: throw IllegalStateException("Unable to open input stream for $uri")

            return outputFile.absolutePath to thumbFile.absolutePath
        } catch (e: Exception) {
            // Don't leave partial/corrupt encrypted files behind
            outputFile.delete()
            thumbFile.delete()
            throw e
        }
    }

    fun decryptThumbnail(path: String, password: String): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null

        val key = getSecretKey(password)
        return try {
            FileInputStream(file).use { fis ->
                ByteArrayOutputStream().use { baos ->
                    if (!decryptToOutputStream(fis, baos, key)) return null
                    val bytes = baos.toByteArray()
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun decryptAndRestore(vaultPhoto: VaultPhoto, password: String): Uri? {
        val inputFile = File(vaultPhoto.path)
        if (!inputFile.exists()) return null

        val key = getSecretKey(password)
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, vaultPhoto.name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (vaultPhoto.videoDuration != null) "video/mp4" else "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Restored")
        }

        val collection = if (vaultPhoto.videoDuration != null) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val uri = context.contentResolver.insert(collection, contentValues) ?: return null

        var success = false
        try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                FileInputStream(inputFile).use { fis ->
                    if (!decryptToOutputStream(fis, output, key)) {
                        context.contentResolver.delete(uri, null, null)
                        return null
                    }
                    success = true
                }
            }
        } catch (e: Exception) {
            if (!success) {
                context.contentResolver.delete(uri, null, null)
            }
            return null
        }

        if (!success) {
            context.contentResolver.delete(uri, null, null)
            return null
        }

        // Delete encrypted files
        inputFile.delete()
        File(vaultPhoto.thumbnailPath).delete()

        return uri
    }
}
