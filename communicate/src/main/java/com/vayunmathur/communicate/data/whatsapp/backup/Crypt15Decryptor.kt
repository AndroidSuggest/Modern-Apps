package com.vayunmathur.communicate.data.whatsapp.backup

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts a local `msgstore.db.crypt15` using the account's 32-byte backup key (entered as 64 hex
 * chars): parse header → HKDF-SHA256 derive the AES key → AES-256-GCM decrypt → gunzip → plain
 * SQLite `msgstore.db` written to [cacheDir].
 *
 * crypt15 layout (per the community `wa-crypt-tools` reference):
 *   [varint headerLen][protobuf BackupPrefix header][AES-256-GCM ciphertext(+16B tag)]
 * where the header's nested `C15_IV` field carries the 16-byte GCM IV. The GCM key is
 *   HKDF-SHA256(ikm = backupKey, salt = 32 zero bytes, info = "backup encryption", L = 32).
 *
 * ⚠️ The exact HKDF `info`/salt and header field numbers are reconstructed from the community tool
 * and MUST be validated against a real backup on-device; a wrong key/derivation surfaces as a GCM
 * auth-tag failure ([javax.crypto.AEADBadTagException]).
 */
object Crypt15Decryptor {

    private const val HKDF_INFO = "backup encryption"

    class DecryptException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** @return the decrypted plaintext SQLite file in [cacheDir]. */
    fun decryptToFile(crypt15: ByteArray, backupKey: ByteArray, cacheDir: File): File {
        if (backupKey.size != 32) throw DecryptException("backup key must be 32 bytes, got ${backupKey.size}")

        val (iv, cipherStart) = parseHeader(crypt15)

        // The exact crypt15 key-derivation varies by reference (WhatsApp's is obfuscated, and
        // wa-crypt-tools documents several variants). Rather than guess one, try each candidate and
        // let AES-256-GCM's auth tag pick the correct key: a wrong key throws AEADBadTagException.
        val candidates = listOf(
            "hkdf(info=\"backup encryption\\x01\")" to
                hkdfSha256(backupKey, ByteArray(32), "backup encryption\u0001".toByteArray(Charsets.ISO_8859_1), 32),
            "hkdf(info=\"backup encryption\")" to
                hkdfSha256(backupKey, ByteArray(32), HKDF_INFO.toByteArray(Charsets.UTF_8), 32),
            "expand-only hmac(key,\"backup encryption\\x01\")" to
                hmacSha256(backupKey, "backup encryption\u0001".toByteArray(Charsets.ISO_8859_1)).copyOf(32),
            "raw root key" to backupKey,
        )

        var lastErr: Throwable? = null
        for ((label, aesKey) in candidates) {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
                val plaintextGz = cipher.doFinal(crypt15, cipherStart, crypt15.size - cipherStart)
                // GCM authenticated → this is the right key. Now gunzip.
                val out = File.createTempFile("msgstore_", ".db", cacheDir)
                GZIPInputStream(ByteArrayInputStream(plaintextGz)).use { gz ->
                    out.outputStream().use { gz.copyTo(it) }
                }
                android.util.Log.i("Crypt15", "decrypted via: $label")
                return out
            } catch (t: Throwable) {
                lastErr = t
            }
        }
        throw DecryptException(
            "decrypt failed for all key derivations (wrong 64-hex key, or crypt15 header/format drift?)",
            lastErr,
        )
    }

    /**
     * Locate the 16-byte GCM IV inside the leading protobuf header and return (iv, ciphertextOffset).
     * The file starts with a varint length for the header protobuf; within it we scan for the nested
     * length-delimited 16-byte field that is the IV. This is defensive against minor field-number
     * drift across backup versions.
     */
    private fun parseHeader(data: ByteArray): Pair<ByteArray, Int> {
        var pos = 0
        val (headerLen, after) = readVarint(data, pos)
        pos = after
        val headerEnd = pos + headerLen.toInt()
        if (headerEnd > data.size) throw DecryptException("header length exceeds file")

        val iv = findSixteenByteField(data, pos, headerEnd)
            ?: throw DecryptException("could not locate 16-byte IV in header")
        return iv to headerEnd
    }

    /** Scan a protobuf region for the first length-delimited (wire type 2) field of exactly 16 bytes. */
    private fun findSixteenByteField(data: ByteArray, start: Int, end: Int): ByteArray? {
        var p = start
        while (p < end) {
            val (tag, afterTag) = readVarint(data, p)
            p = afterTag
            when ((tag and 0x7).toInt()) {
                0 -> { // varint
                    p = readVarint(data, p).second
                }
                2 -> { // length-delimited
                    val (len, afterLen) = readVarint(data, p)
                    p = afterLen
                    val l = len.toInt()
                    if (l == 16 && p + 16 <= end) return data.copyOfRange(p, p + 16)
                    // Recurse into nested messages to find a nested C15_IV.
                    if (p + l <= end) {
                        findSixteenByteField(data, p, p + l)?.let { return it }
                    }
                    p += l
                }
                5 -> p += 4 // fixed32
                1 -> p += 8 // fixed64
                else -> return null
            }
        }
        return null
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var p = start
        while (p < data.size) {
            val b = data[p].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            p++
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result to p
    }

    private fun hmacSha256(key: ByteArray, msg: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(msg)
    }

    /** HKDF-SHA256 (RFC 5869) extract+expand. */
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // extract
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        // expand
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var generated = 0
        var counter = 1
        while (generated < length) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val toCopy = minOf(t.size, length - generated)
            System.arraycopy(t, 0, out, generated, toCopy)
            generated += toCopy
            counter++
        }
        return out
    }
}
