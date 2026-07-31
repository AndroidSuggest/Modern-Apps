package com.vayunmathur.vpn.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.vpn.R
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.vpn.Route
import com.vayunmathur.vpn.util.VpnViewModel
import com.vayunmathur.library.util.NavBackStack

@Composable
fun SettingsPage(backStack: NavBackStack<Route>, vm: VpnViewModel) {
    val context = LocalContext.current
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_about)) }, navigationIcon = { IconNavigation(backStack) }) }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Always-On card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.always_on_vpn), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.enable_always_on_in_android_system_vpn_s) +
                        "Go to Settings → Network & Internet → VPN → gear next to this app → enable Always-On VPN.\n\n" +
                        "This app declares SUPPORTS_ALWAYS_ON and uses START_STICKY with last-used tunnel restore on null intent.",
                        fontSize = 12.sp,
                    )
                    Button(onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                        } catch (_: Exception) {
                            try {
                                context.startActivity(Intent(Settings.ACTION_SETTINGS))
                            } catch (_: Exception) {}
                        }
                    }) {
                        Text(stringResource(R.string.open_system_vpn_settings))
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.vpn_wireguard_gotatun_boringtun_fork), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.this_app_ships_a_pure_rust_implementatio) +
                        "Mullvad's gotatun repository (https://github.com/mullvad/gotatun), itself a " +
                        "fork of Cloudflare's boringtun.\n\n" +
                        "Gotatun implements the Noise IK handshake (X25519, ChaCha20-Poly1305, " +
                        "BLAKE2s), DoS-mitigation cookies (XChaCha20Poly1305), and session handling " +
                        "with rekey/keepalive timers and an anti-replay window. " +
                        "The Rust crate in vpn/src/main/rust vendores gotatun's noise/, packet/, " +
                        "crypto/, tun/ modules with ring/aws-lc-rs shimmed to " +
                        "pure chacha20poly1305 so the aarch64-linux-android cdylib compiles.\n\n" +
                        "Android side: a foreground VpnService (VpnTunnelService) drives the Rust Tunn " +
                        "via JNI. It owns a handle from VpnNative.newTunnel(...). " +
                        "The loop bridges TUN fd (plaintext IP) <-> encapsulate and UDP " +
                        "(encrypted WG) <-> consumeIncomingPacketDetailed. Timer ticks every 100ms emit " +
                        "keepalives and handshake retransmits.\n\n" +
                        "Logging: packet inspection in Kotlin on plaintext IP from TUN (IPv4/IPv6 UDP/TCP parsing), DNS snooping " +
                        "for UDP dport 53 to map IP→domain, SNI extraction from TLS ClientHello for DoH fallback, per-app " +
                        "attribution via ConnectivityManager.getConnectionOwnerUid(). Batched 1.5s upserts to encrypted Room.\n\n" +
                        "Config storage: Room encrypted db (vpn-db) via :library:room / SQLCipher — each " +
                        "config stores Interface Address/DNS/MTU and Peer PublicKey/PresharedKey/AllowedIPs/" +
                        "Endpoint/PersistentKeepalive plus device Private/Public keys. " +
                        "Import supports wg-quick .conf paste and export produces a valid wg-quick file.\n\n" +
                        "Licence: gotatun is MPL-2.0 (previously BSD-3 for older Cloudflare parts); " +
                        "this module is MPL-2.0.\n\n" +
                        "WireGuard is a registered trademark of Jason A. Donenfeld.",
                        fontSize = 12.sp,
                    )
                    Text(
                        stringResource(R.string.emulator_without_ndk_rust_built_so_will) +
                        ":vpn:assembleDev builds libvpn_wireguard.so into rustJniLibs/arm64-v8a.",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
