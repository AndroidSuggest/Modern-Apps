package com.vayunmathur.vpn.service

import java.net.InetAddress

/**
 * Pure Kotlin IP packet parser for TUN data path.
 * Supports IPv4 and IPv6, UDP and TCP.
 * Zero external dependency; bounds-checked for hot path.
 */
object PacketInspector {

    data class ParsedPacket(
        val protocolNumber: Int,
        val protocol: String,
        val srcIp: String,
        val dstIp: String,
        val srcPort: Int,
        val dstPort: Int,
        val payloadOffset: Int,
        val payloadLength: Int,
        val totalLength: Int,
        val isTcpSyn: Boolean = false,
        val isTcpFinOrRst: Boolean = false,
    )

    fun parse(packet: ByteArray): ParsedPacket? {
        if (packet.isEmpty()) return null
        val version = (packet[0].toInt() shr 4) and 0xF
        return when (version) {
            4 -> parseIpv4(packet)
            6 -> parseIpv6(packet)
            else -> null
        }
    }

    private fun parseIpv4(b: ByteArray): ParsedPacket? {
        if (b.size < 20) return null
        val ihl = (b[0].toInt() and 0xF) * 4
        if (ihl < 20 || b.size < ihl) return null
        val totalLen = ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
        if (totalLen < ihl || totalLen > b.size) {
            // Some TUN implementations give full buffer larger than totalLen; use min
            // but keep bounds safe
        }
        val protoNum = b[9].toInt() and 0xFF
        val srcIp = ipv4ToString(b, 12)
        val dstIp = ipv4ToString(b, 16)
        return parseTransport(b, ihl, protoNum, srcIp, dstIp, totalLen.coerceAtMost(b.size))
    }

    private fun parseIpv6(b: ByteArray): ParsedPacket? {
        if (b.size < 40) return null
        val payloadLen = ((b[4].toInt() and 0xFF) shl 8) or (b[5].toInt() and 0xFF)
        val nextHeader = b[6].toInt() and 0xFF
        val totalLen = 40 + payloadLen
        val srcIp = ipv6ToString(b, 8)
        val dstIp = ipv6ToString(b, 24)
        return parseTransport(b, 40, nextHeader, srcIp, dstIp, totalLen.coerceAtMost(b.size))
    }

    private fun parseTransport(
        b: ByteArray,
        ipHeaderLen: Int,
        protoNum: Int,
        srcIp: String,
        dstIp: String,
        totalLen: Int,
    ): ParsedPacket? {
        val proto = when (protoNum) {
            6 -> "TCP"
            17 -> "UDP"
            1 -> "ICMP"
            58 -> "ICMPv6"
            else -> "OTHER"
        }
        if (b.size < ipHeaderLen) return null
        val remaining = b.size - ipHeaderLen
        when (protoNum) {
            17 -> { // UDP
                if (remaining < 8) return null
                val srcPort = ((b[ipHeaderLen].toInt() and 0xFF) shl 8) or (b[ipHeaderLen + 1].toInt() and 0xFF)
                val dstPort = ((b[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or (b[ipHeaderLen + 3].toInt() and 0xFF)
                val payloadOff = ipHeaderLen + 8
                val payloadLen = (totalLen - ipHeaderLen - 8).coerceAtLeast(0)
                return ParsedPacket(
                    protocolNumber = protoNum,
                    protocol = proto,
                    srcIp = srcIp,
                    dstIp = dstIp,
                    srcPort = srcPort,
                    dstPort = dstPort,
                    payloadOffset = payloadOff,
                    payloadLength = payloadLen.coerceAtMost(b.size - payloadOff),
                    totalLength = totalLen,
                )
            }
            6 -> { // TCP
                if (remaining < 20) return null
                val srcPort = ((b[ipHeaderLen].toInt() and 0xFF) shl 8) or (b[ipHeaderLen + 1].toInt() and 0xFF)
                val dstPort = ((b[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or (b[ipHeaderLen + 3].toInt() and 0xFF)
                val dataOffset = (b[ipHeaderLen + 12].toInt() shr 4) and 0xF
                val tcpHeaderLen = dataOffset * 4
                if (tcpHeaderLen < 20) return null
                if (b.size < ipHeaderLen + tcpHeaderLen) return null
                val flags = b[ipHeaderLen + 13].toInt() and 0xFF
                val isSyn = (flags and 0x02) != 0
                val isFin = (flags and 0x01) != 0
                val isRst = (flags and 0x04) != 0
                val payloadOff = ipHeaderLen + tcpHeaderLen
                val payloadLen = (totalLen - ipHeaderLen - tcpHeaderLen).coerceAtLeast(0)
                return ParsedPacket(
                    protocolNumber = protoNum,
                    protocol = proto,
                    srcIp = srcIp,
                    dstIp = dstIp,
                    srcPort = srcPort,
                    dstPort = dstPort,
                    payloadOffset = payloadOff,
                    payloadLength = payloadLen.coerceAtMost(b.size - payloadOff),
                    totalLength = totalLen,
                    isTcpSyn = isSyn,
                    isTcpFinOrRst = isFin || isRst,
                )
            }
            else -> {
                // Non TCP/UDP - no ports
                return ParsedPacket(
                    protocolNumber = protoNum,
                    protocol = proto,
                    srcIp = srcIp,
                    dstIp = dstIp,
                    srcPort = 0,
                    dstPort = 0,
                    payloadOffset = ipHeaderLen,
                    payloadLength = (totalLen - ipHeaderLen).coerceAtLeast(0).coerceAtMost(b.size - ipHeaderLen),
                    totalLength = totalLen,
                )
            }
        }
    }

    private fun ipv4ToString(b: ByteArray, off: Int): String {
        return try {
            InetAddress.getByAddress(b.copyOfRange(off, off + 4)).hostAddress ?: "${b[off].toInt() and 0xFF}.${b[off + 1].toInt() and 0xFF}.${b[off + 2].toInt() and 0xFF}.${b[off + 3].toInt() and 0xFF}"
        } catch (_: Exception) {
            "${b[off].toInt() and 0xFF}.${b[off + 1].toInt() and 0xFF}.${b[off + 2].toInt() and 0xFF}.${b[off + 3].toInt() and 0xFF}"
        }
    }

    private fun ipv6ToString(b: ByteArray, off: Int): String {
        return try {
            InetAddress.getByAddress(b.copyOfRange(off, off + 16)).hostAddress ?: ""
        } catch (_: Exception) {
            // Fallback manual
            val parts = mutableListOf<String>()
            for (i in 0 until 16 step 2) {
                val v = ((b[off + i].toInt() and 0xFF) shl 8) or (b[off + i + 1].toInt() and 0xFF)
                parts.add(v.toString(16))
            }
            parts.joinToString(":")
        }
    }
}
