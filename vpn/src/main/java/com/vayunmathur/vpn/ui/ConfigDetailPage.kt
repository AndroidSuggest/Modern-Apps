package com.vayunmathur.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.vpn.Route
import com.vayunmathur.vpn.data.WgConfigParser
import com.vayunmathur.vpn.util.VpnViewModel
import com.vayunmathur.library.util.NavBackStack

/**
 * Read-only detail for an imported .conf tunnel — the only way to add is opening a .conf file.
 * Export as wg-quick .conf is supported.
 */
@Composable
fun ConfigDetailPage(backStack: NavBackStack<Route>, vm: VpnViewModel, id: Long) {
    val cfg = vm.configState(id)
    var exportText by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Tunnel — ${cfg.name}") }, navigationIcon = { IconNavigation(backStack) })
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Imported from WireGuard .conf using gotatun (mullvad/gotatun) — Noise IK / X25519 + ChaCha20Poly1305. To update, import a new .conf file.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Name: ${cfg.name}", fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text("Endpoint: ${cfg.peerEndpoint}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text("Address: ${cfg.address}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text("DNS: ${cfg.dns}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text("AllowedIPs: ${cfg.peerAllowedIPs}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text("MTU: ${cfg.mtu}  Keepalive: ${cfg.peerKeepalive}s", fontSize = 12.sp)
            Text("PrivateKey: ${cfg.privateKey.take(16)}…", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("PublicKey: ${cfg.publicKey}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text("Peer PublicKey: ${cfg.peerPublicKey}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)

            Button({ exportText = WgConfigParser.toWgQuick(cfg) }, Modifier.fillMaxWidth()) { Text("Export as .conf") }

            if (exportText != null) {
                Text(exportText!!, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(64.dp))
        }
    }
}
