package com.vayunmathur.vpn.service

/**
 * Extracts SNI from TLS ClientHello for domain recovery when DoH is used.
 */
object SniParser {

    /**
     * Attempts to extract the server name from a TLS payload.
     * @param payload full TCP payload containing TLS handshake
     * @param off offset in payload where TLS data starts (typically same as TCP payload offset)
     * @return domain or null
     */
    fun extractSni(packet: ByteArray, payloadOffset: Int, payloadLen: Int): String? {
        if (payloadLen < 10) return null
        if (payloadOffset + payloadLen > packet.size) return null
        val p = packet
        var pos = payloadOffset

        try {
            // TLS record: [0]=0x16 (handshake), [1..2]=version, [3..4]=length
            if ((p[pos].toInt() and 0xFF) != 0x16) return null
            // record length
            // val recordLen = ((p[pos+3].toInt() and 0xFF) shl 8) or (p[pos+4].toInt() and 0xFF)
            pos += 5
            if (pos >= packet.size) return null
            // Handshake: [0]=msgType 1=ClientHello
            if ((p[pos].toInt() and 0xFF) != 1) return null
            // Handshake length 3 bytes
            val hsLen = ((p[pos + 1].toInt() and 0xFF) shl 16) or ((p[pos + 2].toInt() and 0xFF) shl 8) or (p[pos + 3].toInt() and 0xFF)
            if (hsLen < 10) return null
            pos += 4
            if (pos + hsLen > packet.size) return null
            // ClientHello: version(2) + random(32) + sessionIdLen(1) ...
            val end = pos + hsLen
            pos += 2 + 32
            if (pos >= end) return null
            val sessionIdLen = p[pos].toInt() and 0xFF
            pos += 1 + sessionIdLen
            if (pos + 2 > end) return null
            val cipherSuitesLen = ((p[pos].toInt() and 0xFF) shl 8) or (p[pos + 1].toInt() and 0xFF)
            pos += 2 + cipherSuitesLen
            if (pos + 1 > end) return null
            val compressionMethodsLen = p[pos].toInt() and 0xFF
            pos += 1 + compressionMethodsLen
            if (pos + 2 > end) return null
            val extensionsLen = ((p[pos].toInt() and 0xFF) shl 8) or (p[pos + 1].toInt() and 0xFF)
            pos += 2
            val extensionsEnd = pos + extensionsLen
            if (extensionsEnd > end || extensionsEnd > packet.size) return null

            while (pos + 4 <= extensionsEnd) {
                val extType = ((p[pos].toInt() and 0xFF) shl 8) or (p[pos + 1].toInt() and 0xFF)
                val extLen = ((p[pos + 2].toInt() and 0xFF) shl 8) or (p[pos + 3].toInt() and 0xFF)
                pos += 4
                if (pos + extLen > extensionsEnd || pos + extLen > packet.size) break

                if (extType == 0) { // server_name
                    // server_name list:
                    // 2 bytes list length
                    if (extLen < 2) {
                        pos += extLen; continue
                    }
                    // val listLen = ((p[pos] & 0xFF)<<8)|(p[pos+1]&0xFF)
                    var sniPos = pos + 2
                    val listEnd = pos + extLen
                    while (sniPos + 3 <= listEnd) {
                        val nameType = p[sniPos].toInt() and 0xFF
                        val nameLen = ((p[sniPos + 1].toInt() and 0xFF) shl 8) or (p[sniPos + 2].toInt() and 0xFF)
                        sniPos += 3
                        if (sniPos + nameLen > listEnd) break
                        if (nameType == 0) { // host_name
                            val domain = try {
                                String(p, sniPos, nameLen, Charsets.UTF_8)
                            } catch (_: Exception) {
                                null
                            }
                            if (!domain.isNullOrBlank() && domain.contains('.')) return domain
                        }
                        sniPos += nameLen
                    }
                }
                pos += extLen
            }
        } catch (_: Exception) {
            return null
        }
        return null
    }
}
