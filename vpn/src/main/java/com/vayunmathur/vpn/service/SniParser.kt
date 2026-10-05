package com.vayunmathur.vpn.service

/**
 * Extracts SNI from TLS ClientHello for domain recovery when DoH is used.
 */
object SniParser {

    private const val TLS_HANDSHAKE_RECORD = 0x16
    private const val TLS_RECORD_HEADER_LEN = 5
    private const val CLIENT_HELLO_MSG = 1
    private const val HANDSHAKE_HEADER_LEN = 4
    private const val MIN_HANDSHAKE_LEN = 10
    private const val CLIENT_HELLO_VERSION_LEN = 2
    private const val CLIENT_HELLO_RANDOM_LEN = 32
    private const val SERVER_NAME_EXT = 0
    private const val HOST_NAME_TYPE = 0
    private const val MIN_PACKET_LEN = 10
    private const val BYTE_MASK = 0xFF
    private const val BYTE_SHIFT = 8
    private const val U24_HIGH_SHIFT = 16
    private const val BYTE_STRIDE = 1
    private const val SHORT_STRIDE = 2
    private const val EXT_HEADER_LEN = 4
    private const val EXT_LEN_OFF = 2
    private const val MIN_NAME_LIST_LEN = 2
    private const val SNI_ENTRY_HEADER_LEN = 3
    private const val SNI_NAME_LEN_OFF = 1

    /**
     * Attempts to extract the server name from a TLS payload.
     * @param packet full TCP payload containing TLS handshake
     * @param payloadOffset offset in payload where TLS data starts
     * @param payloadLen length of TLS data
     * @return domain or null
     */
    fun extractSni(packet: ByteArray, payloadOffset: Int, payloadLen: Int): String? {
        if (payloadLen < MIN_PACKET_LEN) return null
        if (payloadOffset + payloadLen > packet.size) return null
        return runCatching {
            val hsStart = skipTlsRecordHeader(packet, payloadOffset) ?: return null
            val (bodyStart, bodyEnd) = handshakeBody(packet, hsStart) ?: return null
            val extStart = skipClientHelloFixed(packet, bodyStart, bodyEnd) ?: return null
            scanExtensions(packet, extStart, bodyEnd)
        }.getOrNull()
    }

    private fun skipTlsRecordHeader(p: ByteArray, pos: Int): Int? {
        // TLS record: [0]=0x16 (handshake), [1..2]=version, [3..4]=length
        if (uByte(p, pos) != TLS_HANDSHAKE_RECORD) return null
        return pos + TLS_RECORD_HEADER_LEN
    }

    private fun handshakeBody(p: ByteArray, pos: Int): Pair<Int, Int>? {
        if (pos >= p.size) return null
        // Handshake: [0]=msgType 1=ClientHello, then 3-byte length
        if (uByte(p, pos) != CLIENT_HELLO_MSG) return null
        val hsLen = u24(p, pos + 1)
        if (hsLen < MIN_HANDSHAKE_LEN) return null
        val body = pos + HANDSHAKE_HEADER_LEN
        val end = body + hsLen
        if (end > p.size) return null
        return body to end
    }

    private fun skipClientHelloFixed(p: ByteArray, pos: Int, end: Int): Int? {
        // ClientHello: version(2) + random(32) + sessionIdLen(1) ...
        var cur = pos + CLIENT_HELLO_VERSION_LEN + CLIENT_HELLO_RANDOM_LEN
        if (cur >= end) return null
        cur += 1 + uByte(p, cur)
        if (cur + 2 > end) return null
        cur += 2 + u16(p, cur)
        if (cur + 1 > end) return null
        cur += 1 + uByte(p, cur)
        if (cur + 2 > end) return null
        val extLen = u16(p, cur)
        cur += 2
        val extEnd = cur + extLen
        if (extEnd > end || extEnd > p.size) return null
        return cur
    }

    private fun scanExtensions(p: ByteArray, extStart: Int, limit: Int): String? {
        var pos = extStart
        while (pos + EXT_HEADER_LEN <= limit) {
            val extType = u16(p, pos)
            val extLen = u16(p, pos + EXT_LEN_OFF)
            pos += EXT_HEADER_LEN
            if (pos + extLen > limit) break
            if (extType == SERVER_NAME_EXT) {
                val found = scanServerNames(p, pos, extLen)
                if (found != null) return found
            }
            pos += extLen
        }
        return null
    }

    private fun scanServerNames(p: ByteArray, pos: Int, extLen: Int): String? {
        // server_name list: 2 bytes list length, then entries
        if (extLen < MIN_NAME_LIST_LEN) return null
        var sniPos = pos + MIN_NAME_LIST_LEN
        val listEnd = pos + extLen
        while (sniPos + SNI_ENTRY_HEADER_LEN <= listEnd) {
            val nameType = uByte(p, sniPos)
            val nameLen = u16(p, sniPos + SNI_NAME_LEN_OFF)
            sniPos += SNI_ENTRY_HEADER_LEN
            if (sniPos + nameLen > listEnd) break
            if (nameType == HOST_NAME_TYPE) {
                val domain = decodeHostName(p, sniPos, nameLen)
                if (domain != null) return domain
            }
            sniPos += nameLen
        }
        return null
    }

    private fun decodeHostName(p: ByteArray, off: Int, len: Int): String? {
        val domain = runCatching {
            String(p, off, len, Charsets.UTF_8)
        }.getOrNull() ?: return null
        if (domain.isBlank() || !domain.contains('.')) return null
        return domain
    }

    private fun uByte(p: ByteArray, pos: Int): Int = p[pos].toInt() and BYTE_MASK

    private fun u16(p: ByteArray, pos: Int): Int =
        (uByte(p, pos) shl BYTE_SHIFT) or uByte(p, pos + BYTE_STRIDE)

    private fun u24(p: ByteArray, pos: Int): Int =
        (uByte(p, pos) shl U24_HIGH_SHIFT) or
            (uByte(p, pos + BYTE_STRIDE) shl BYTE_SHIFT) or
            uByte(p, pos + SHORT_STRIDE)
}
