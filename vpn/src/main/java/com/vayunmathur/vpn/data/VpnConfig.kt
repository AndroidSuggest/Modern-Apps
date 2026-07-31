package com.vayunmathur.vpn.data

import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable

@Serializable
data class VpnConfig(
    override val id: Long = 0,
    val name: String = "",
    val privateKey: String = "",
    val publicKey: String = "",
    val address: String = "",
    val dns: String = "",
    val mtu: Int = 1280,
    val peerPublicKey: String = "",
    val peerPresharedKey: String = "",
    val peerAllowedIPs: String = "0.0.0.0/0, ::/0",
    val peerEndpoint: String = "",
    val peerKeepalive: Int = 25,
    val lastUsed: Long = 0,
) : DatabaseItem

data class WgQuickImport(
    val privateKey: String,
    val address: String,
    val dns: String,
    val mtu: Int,
    val peerPublicKey: String,
    val peerPresharedKey: String,
    val peerAllowedIps: String,
    val peerEndpoint: String,
    val peerKeepalive: Int,
)

object WgConfigParser {
    fun parse(confText: String): Result<WgQuickImport> = runCatching {
        val sections = mutableMapOf<String, MutableMap<String, String>>()
        var cur = ""
        confText.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@forEach
            if (line.startsWith("[") && line.endsWith("]")) {
                cur = line.removeSurrounding("[", "]").trim()
                sections.getOrPut(cur) { mutableMapOf() }
            } else {
                val idx = line.indexOf('=')
                if (idx > 0 && cur.isNotEmpty()) {
                    sections[cur]?.set(line.substring(0, idx).trim().lowercase(), line.substring(idx + 1).trim())
                }
            }
        }
        val iface = sections["Interface"] ?: error("Missing [Interface]")
        val peer = sections["Peer"] ?: error("Missing [Peer]")
        WgQuickImport(
            privateKey = iface["privatekey"] ?: error("Missing PrivateKey"),
            address = iface["address"] ?: "",
            dns = iface["dns"] ?: "",
            mtu = iface["mtu"]?.toIntOrNull() ?: 1280,
            peerPublicKey = peer["publickey"] ?: error("Missing Peer PublicKey"),
            peerPresharedKey = peer["presharedkey"] ?: "",
            peerAllowedIps = peer["allowedips"] ?: "0.0.0.0/0, ::/0",
            peerEndpoint = peer["endpoint"] ?: "",
            peerKeepalive = peer["persistentkeepalive"]?.toIntOrNull() ?: 25,
        )
    }

    fun toWgQuick(c: VpnConfig): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = ${c.privateKey}")
        if (c.address.isNotBlank()) appendLine("Address = ${c.address}")
        if (c.dns.isNotBlank()) appendLine("DNS = ${c.dns}")
        if (c.mtu != 0) appendLine("MTU = ${c.mtu}")
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = ${c.peerPublicKey}")
        if (c.peerPresharedKey.isNotBlank()) appendLine("PresharedKey = ${c.peerPresharedKey}")
        appendLine("AllowedIPs = ${c.peerAllowedIPs}")
        if (c.peerEndpoint.isNotBlank()) appendLine("Endpoint = ${c.peerEndpoint}")
        if (c.peerKeepalive > 0) appendLine("PersistentKeepalive = ${c.peerKeepalive}")
    }
}

fun VpnConfig.endpointHost(): String = peerEndpoint.substringBefore(':').trim()
fun VpnConfig.endpointPort(): Int = peerEndpoint.substringAfterLast(':').toIntOrNull() ?: 51820

data class VpnStats(
    val handshakeAgoMs: Long = 0,
    val txBytes: Long = 0,
    val rxBytes: Long = 0,
    val loss: Float = 0f,
    val rttMs: Int = 0,
)
