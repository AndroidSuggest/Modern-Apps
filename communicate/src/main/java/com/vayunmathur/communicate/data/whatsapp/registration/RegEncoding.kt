package com.vayunmathur.communicate.data.whatsapp.registration

import android.util.Base64
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Byte/string encoders that mirror WhatsApp's `C34244EyE` registration request builder exactly, so
 * `/v2/ endpoints` params are byte-for-byte what the server expects (avoids `bad_param`).
 *
 * Verified against the pinned APK:
 *  - [b64Url]  = `DIj.A0w` = `Base64.encodeToString(b, URL_SAFE|NO_WRAP|NO_PADDING)` (flag 11).
 *              Used by `A03`(expid/access_session_id after UUID→bytes) and `A04`(the E2E key bundle).
 *  - [percentEncode] = `EPJ.A00` = RFC-3986 percent-encoding keeping unreserved `A-Za-z0-9-._~`.
 *              Used by `A05`(id, backup_token); the result is stored PRE-ENCODED and must not be
 *              URL-encoded again at query-build time.
 *  - [uuidToBytes] = the 16-byte big-endian form `A03` derives from a UUID string.
 */
object RegEncoding {

    private const val UUID_BYTES = 16
    private const val BYTE_MASK = 0xFF
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0xF
    private val DIGIT_RANGE = 0x30..0x39
    private val UPPER_RANGE = 0x41..0x5A
    private val LOWER_RANGE = 0x61..0x7A
    private const val HYPHEN = 0x2D
    private const val DOT = 0x2E
    private const val UNDERSCORE = 0x5F
    private const val TILDE = 0x7E

    /** URL-safe Base64, no padding, no wrap (Android Base64 flag 11). */
    fun b64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /** 16 big-endian bytes of a UUID (matches C34244EyE.A03). */
    fun uuidToBytes(uuid: String): ByteArray {
        val u = UUID.fromString(uuid)
        return ByteBuffer.allocate(UUID_BYTES)
            .putLong(u.mostSignificantBits)
            .putLong(u.leastSignificantBits)
            .array()
    }

    /** RFC-3986 percent-encoding of raw bytes, unreserved set = A-Za-z0-9-._~ (matches EPJ.A00). */
    fun percentEncode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            val i = b.toInt() and BYTE_MASK
            val c = i.toChar()
            val unreserved = (i in DIGIT_RANGE) || // 0-9
                (i in UPPER_RANGE) || // A-Z
                (i in LOWER_RANGE) || // a-z
                i == HYPHEN || i == DOT || i == UNDERSCORE || i == TILDE // - . _ ~
            if (unreserved) {
                sb.append(c)
            } else {
                sb.append('%')
                sb.append(HEX[i shr NIBBLE_BITS])
                sb.append(HEX[i and NIBBLE_MASK])
            }
        }
        return sb.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
