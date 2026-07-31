package com.vayunmathur.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.vpn.Route
import com.vayunmathur.vpn.data.VpnConfig
import com.vayunmathur.vpn.data.WgConfigParser
import com.vayunmathur.vpn.util.VpnViewModel
import com.vayunmathur.library.util.NavBackStack

@Composable
fun ConfigDetailPage(backStack: NavBackStack<Route>, vm: VpnViewModel, id: Long) {
    val cfg = vm.configState(id)

    var name by remember(cfg) { mutableStateOf(cfg.name) }
    var privateKey by remember(cfg) { mutableStateOf(cfg.privateKey) }
    var publicKey by remember(cfg) { mutableStateOf(cfg.publicKey) }
    var address by remember(cfg) { mutableStateOf(cfg.address) }
    var dns by remember(cfg) { mutableStateOf(cfg.dns) }
    var mtu by remember(cfg) { mutableStateOf(cfg.mtu.toString()) }
    var peerPublicKey by remember(cfg) { mutableStateOf(cfg.peerPublicKey) }
    var peerPsk by remember(cfg) { mutableStateOf(cfg.peerPresharedKey) }
    var peerAllowed by remember(cfg) { mutableStateOf(cfg.peerAllowedIPs) }
    var peerEndpoint by remember(cfg) { mutableStateOf(cfg.peerEndpoint) }
    var keepalive by remember(cfg) { mutableStateOf(cfg.peerKeepalive.toString()) }
    var exportText by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (id == 0L) "New Tunnel" else "Edit Tunnel") },
                navigationIcon = { IconNavigation(backStack) },
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Based on gotatun / BoringTun fork — WireGuard protocol: X25519, ChaCha20Poly1305, BLAKE2s. Repo: mullvad/gotatun.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") })

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    val (priv, pub) = vm.generateKeys()
                    if (priv.isNotEmpty()) { privateKey = priv; publicKey = pub }
                }, Modifier.weight(1f)) { Text("Generate Keys") }
                Button({
                    if (privateKey.isNotBlank()) {
                        runCatching { com.vayunmathur.vpn.util.VpnNative.derivePublicKey(privateKey) }
                            .onSuccess { publicKey = it ?: "" }
                    }
                }, Modifier.weight(1f)) { Text("Derive Pub") }
            }

            OutlinedTextField(privateKey, { privateKey = it }, Modifier.fillMaxWidth(), label = { Text("PrivateKey (base64)") })
            OutlinedTextField(publicKey, { publicKey = it }, Modifier.fillMaxWidth(), label = { Text("PublicKey (auto)") }, readOnly = true)

            OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("Address (CSV CIDR), e.g. 10.0.0.2/32") })
            OutlinedTextField(dns, { dns = it }, Modifier.fillMaxWidth(), label = { Text("DNS (CSV), e.g. 1.1.1.1, 8.8.8.8") })
            OutlinedTextField(mtu, { mtu = it }, Modifier.fillMaxWidth(), label = { Text("MTU (1280)") })

            Text("Peer — your server", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(peerPublicKey, { peerPublicKey = it }, Modifier.fillMaxWidth(), label = { Text("Peer PublicKey (base64)") })
            OutlinedTextField(peerPsk, { peerPsk = it }, Modifier.fillMaxWidth(), label = { Text("PresharedKey (optional, base64)") })
            OutlinedTextField(peerAllowed, { peerAllowed = it }, Modifier.fillMaxWidth(), label = { Text("AllowedIPs (CSV), e.g. 0.0.0.0/0") })
            OutlinedTextField(peerEndpoint, { peerEndpoint = it }, Modifier.fillMaxWidth(), label = { Text("Endpoint host:port, e.g. 1.2.3.4:51820") })
            OutlinedTextField(keepalive, { keepalive = it }, Modifier.fillMaxWidth(), label = { Text("PersistentKeepalive (25)") })

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    val cleaned = VpnConfig(
                        id = id, name = name.trim(), privateKey = privateKey.trim(), publicKey = publicKey.trim(),
                        address = address.trim(), dns = dns.trim(),
                        mtu = mtu.toIntOrNull() ?: 1280,
                        peerPublicKey = peerPublicKey.trim(), peerPresharedKey = peerPsk.trim(),
                        peerAllowedIPs = peerAllowed.trim(), peerEndpoint = peerEndpoint.trim(),
                        peerKeepalive = keepalive.toIntOrNull() ?: 25,
                    )
                    vm.upsert(cleaned) { backStack.pop() }
                }, Modifier.weight(1f)) { Text("Save") }

                Button({
                    if (id != 0L) {
                        val cleaned = VpnConfig(
                            id = id, name = name.trim(), privateKey = privateKey.trim(), publicKey = publicKey.trim(),
                            address = address.trim(), dns = dns.trim(),
                            mtu = mtu.toIntOrNull() ?: 1280,
                            peerPublicKey = peerPublicKey.trim(), peerPresharedKey = peerPsk.trim(),
                            peerAllowedIPs = peerAllowed.trim(), peerEndpoint = peerEndpoint.trim(),
                            peerKeepalive = keepalive.toIntOrNull() ?: 25,
                        )
                        exportText = WgConfigParser.toWgQuick(cleaned)
                    }
                }, Modifier.weight(1f)) { Text("Export") }
            }

            if (exportText != null) {
                Text(exportText!!, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(64.dp))
        }
    }
}
