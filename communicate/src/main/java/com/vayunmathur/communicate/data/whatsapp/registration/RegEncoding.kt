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

    /** URL-safe Base64, no padding, no wrap (Android Base64 flag 11). */
    fun b64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /** 16 big-endian bytes of a UUID (matches C34244EyE.A03). */
    fun uuidToBytes(uuid: String): ByteArray {
        val u = UUID.fromString(uuid)
        return ByteBuffer.allocate(16)
            .putLong(u.mostSignificantBits)
            .putLong(u.leastSignificantBits)
            .array()
    }

    /** RFC-3986 percent-encoding of raw bytes, unreserved set = A-Za-z0-9-._~ (matches EPJ.A00). */
    fun percentEncode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            val i = b.toInt() and 0xFF
            val c = i.toChar()
            val unreserved = (i in 0x30..0x39) || // 0-9
                (i in 0x41..0x5A) || // A-Z
                (i in 0x61..0x7A) || // a-z
                i == 0x2D || i == 0x2E || i == 0x5F || i == 0x7E // - . _ ~
            if (unreserved) {
                sb.append(c)
            } else {
                sb.append('%')
                sb.append(HEX[i shr 4])
                sb.append(HEX[i and 0xF])
            }
        }
        return sb.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
