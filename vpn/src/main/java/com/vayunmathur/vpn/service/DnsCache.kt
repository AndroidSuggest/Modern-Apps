package com.vayunmathur.vpn.service

import java.net.InetAddress
import java.util.LinkedHashMap

/**
 * DNS snooping: observes UDP port 53 traffic (queries and responses) to build IP -> domain map.
 * Also handles SNI fallback via [SniParser].
 */
class DnsCache(private val maxSize: Int = 1500) {

    // LRU IP -> Domain
    private val ipToDomain = object : LinkedHashMap<String, String>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > maxSize
    }

    // Transaction ID -> query domains (for response correlation)
    private val txIdToDomains = mutableMapOf<Int, MutableList<String>>()
    private val txIdMaxSize = 500

    // Synchronize via @Synchronized; called from single tunnel thread but keep safe.
    @Synchronized
    fun put(ip: String, domain: String) {
        if (domain.isBlank()) return
        // Only overwrite if new or we want fresher - always put
        ipToDomain[ip] = domain
    }

    @Synchronized
    fun get(ip: String): String? = ipToDomain[ip]

    @Synchronized
    fun clear() {
        ipToDomain.clear()
        txIdToDomains.clear()
    }

    @Synchronized
    fun allEntries(): Map<String, String> = LinkedHashMap(ipToDomain)

    /**
     * Called from tunnel thread for each parsed packet that is UDP with port 53 src or dst.
     * Returns a DNS-related domain if observed (for direct logging), and side-effects populating cache.
     */
    @Synchronized
    fun onPacket(parsed: PacketInspector.ParsedPacket, packet: ByteArray): String? {
        // Need UDP payload
        if (parsed.protocol != "UDP") return null
        if (parsed.payloadLength < 12) return null
        if (parsed.payloadOffset + parsed.payloadLength > packet.size) return null

        val payloadOff = parsed.payloadOffset
        val isQuery = (parsed.srcPort == 53).not() && parsed.dstPort == 53
        val isResponse = parsed.srcPort == 53

        return try {
            when {
                isQuery -> handleDnsQuery(packet, payloadOff)
                isResponse -> handleDnsResponse(packet, payloadOff)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun handleDnsQuery(packet: ByteArray, off: Int): String? {
        // DNS header: [0..1] txid, [2..11] flags+counts, [12..] QNAME
        val txId = ((packet[off].toInt() and 0xFF) shl 8) or (packet[off + 1].toInt() and 0xFF)
        val qdCount = ((packet[off + 4].toInt() and 0xFF) shl 8) or (packet[off + 5].toInt() and 0xFF)
        if (qdCount != 1 && qdCount != 2) {
            // allow up to 2; but proceed
        }
        var pos = off + 12
        val domains = mutableListOf<String>()
        repeat(qdCount.coerceAtMost(4)) {
            val (domain, newPos) = decodeName(packet, pos, off) ?: return@repeat
            if (domain.isNotBlank()) domains.add(domain)
            // QTYPE(2) + QCLASS(2)
            pos = newPos + 4
        }
        if (domains.isNotEmpty()) {
            if (txIdToDomains.size > txIdMaxSize) {
                // evict oldest entry
                txIdToDomains.keys.firstOrNull()?.let { txIdToDomains.remove(it) }
            }
            txIdToDomains[txId] = domains.toMutableList()
            return domains.firstOrNull()
        }
        return null
    }

    private fun handleDnsResponse(packet: ByteArray, off: Int): String? {
        val txId = ((packet[off].toInt() and 0xFF) shl 8) or (packet[off + 1].toInt() and 0xFF)
        val anCount = ((packet[off + 6].toInt() and 0xFF) shl 8) or (packet[off + 7].toInt() and 0xFF)
        if (anCount == 0) return null

        val queryDomains = txIdToDomains[txId] ?: return null
        val responseDomain = queryDomains.firstOrNull() ?: return null

        var pos = off + 12
        // Skip questions
        val qdCount = ((packet[off + 4].toInt() and 0xFF) shl 8) or (packet[off + 5].toInt() and 0xFF)
        repeat(qdCount.coerceAtMost(4)) {
            val (_, newPos) = decodeName(packet, pos, off) ?: return@repeat
            pos = newPos + 4
        }
        // Parse answers for A/AAAA
        repeat(anCount.coerceAtMost(50)) {
            if (pos + 10 >= packet.size) return@repeat
            val (_, nameEnd) = decodeName(packet, pos, off) ?: run {
                pos += 10
                return@repeat
            }
            pos = nameEnd
            if (pos + 10 > packet.size) return@repeat
            val rtype = ((packet[pos].toInt() and 0xFF) shl 8) or (packet[pos + 1].toInt() and 0xFF)
            // val rclass = ((packet[pos+2] & 0xFF)<<8)|(packet[pos+3]&0xFF)
            // val ttl = 4 bytes pos+4..pos+7
            val rdLength = ((packet[pos + 8].toInt() and 0xFF) shl 8) or (packet[pos + 9].toInt() and 0xFF)
            pos += 10
            if (pos + rdLength > packet.size) return@repeat
            when (rtype) {
                1 -> { // A record
                    if (rdLength == 4) {
                        try {
                            val ip = InetAddress.getByAddress(packet.copyOfRange(pos, pos + 4)).hostAddress ?: ""
                            if (ip.isNotBlank()) ipToDomain[ip] = responseDomain
                        } catch (_: Exception) {}
                    }
                }
                28 -> { // AAAA
                    if (rdLength == 16) {
                        try {
                            val ip = InetAddress.getByAddress(packet.copyOfRange(pos, pos + 16)).hostAddress ?: ""
                            if (ip.isNotBlank()) ipToDomain[ip] = responseDomain
                        } catch (_: Exception) {}
                    }
                }
            }
            pos += rdLength
        }
        // Clean up used txId
        txIdToDomains.remove(txId)
        return responseDomain
    }

    /**
     * Decodes DNS QNAME at [pos] with compression pointer support (0xC0).
     * Returns pair (domainString, positionAfterName).
     */
    private fun decodeName(packet: ByteArray, startPos: Int, packetOffset: Int): Pair<String, Int>? {
        val labels = mutableListOf<String>()
        var pos = startPos
        var jumped = false
        var jumpEndPos = -1
        var loops = 0
        while (pos < packet.size) {
            if (loops++ > 64) return null // prevent infinite loop
            val lenByte = packet[pos].toInt() and 0xFF
            when {
                lenByte == 0 -> {
                    pos++
                    break
                }
                (lenByte and 0xC0) == 0xC0 -> {
                    if (pos + 1 >= packet.size) return null
                    val pointer = ((lenByte and 0x3F) shl 8) or (packet[pos + 1].toInt() and 0xFF)
                    val target = packetOffset + pointer
                    if (target < packetOffset || target >= packet.size) return null
                    if (!jumped) {
                        jumpEndPos = pos + 2
                    }
                    jumped = true
                    pos = target
                }
                lenByte < 64 -> {
                    val labelLen = lenByte
                    pos++
                    if (pos + labelLen > packet.size) return null
                    val label = try {
                        String(packet, pos, labelLen, Charsets.UTF_8)
                    } catch (_: Exception) {
                        return null
                    }
                    labels.add(label)
                    pos += labelLen
                }
                else -> return null
            }
        }
        val finalPos = if (jumped && jumpEndPos != -1) jumpEndPos else pos
        val domain = labels.joinToString(".")
        return Pair(domain, finalPos)
    }
}
