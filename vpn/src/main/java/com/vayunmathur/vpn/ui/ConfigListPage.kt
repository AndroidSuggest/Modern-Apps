package com.vayunmathur.vpn.ui

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.SnackbarHost
import com.vayunmathur.library.ui.SnackbarHostState
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.vpn.Route
import com.vayunmathur.vpn.data.VpnConfig
import com.vayunmathur.vpn.service.VpnTunnelService
import com.vayunmathur.vpn.util.VpnViewModel
import com.vayunmathur.library.util.NavBackStack

@Composable
fun ConfigListPage(backStack: NavBackStack<Route>, vm: VpnViewModel) {
    val configs by vm.configs.collectAsState()
    val connectingId by vm.connectingId.collectAsState()
    val status by vm.status.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity
    val snackbar = remember { SnackbarHostState() }
    var showImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }

    LaunchedEffect(status) {
        status?.let { snackbar.showSnackbar(it); vm.clearStatus() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VPN — WireGuard (gotatun)") },
                actions = {
                    androidx.compose.material3.IconButton({ backStack.add(Route.Settings) }) {
                        IconSettings()
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FloatingActionButton(onClick = { showImport = true }) {
                    Text("Import")
                }
                FloatingActionButton(onClick = { backStack.add(Route.New) }) {
                    IconAdd()
                }
            }
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (configs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No tunnels yet", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Create one or Import a wg-quick .conf")
                        Spacer(Modifier.height(16.dp))
                        Button({ backStack.add(Route.New) }) { Text("New tunnel") }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(configs, key = { it.id }) { cfg ->
                        ConfigRow(
                            cfg,
                            isActive = connectingId == cfg.id && VpnTunnelService.isRunning,
                            isConnecting = connectingId == cfg.id,
                            onConnect = {
                                if (connectingId != null && VpnTunnelService.isRunning) {
                                    vm.stopVpn()
                                } else {
                                    if (activity != null) vm.startVpn(activity, cfg)
                                    else Toast.makeText(context, "Need Activity to grant VPN permission", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onClick = { backStack.add(Route.Detail(cfg.id)) },
                            onDelete = { vm.delete(cfg) },
                        )
                    }
                }
            }
        }
    }

    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("Import wg-quick .conf") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Paste a full WireGuard config (gotatun protocol compatible): [Interface] + [Peer]. Host:port Endpoint + base64 keys.", fontSize = 12.sp)
                    OutlinedTextField(
                        importText,
                        { importText = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("config text") },
                        minLines = 6,
                        maxLines = 12,
                    )
                }
            },
            confirmButton = {
                TextButton({
                    vm.importFromText(importText)
                    showImport = false
                    importText = ""
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton({ showImport = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ConfigRow(
    cfg: VpnConfig,
    isActive: Boolean,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = if (isActive) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(cfg.name.ifBlank { "Unnamed" }, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(cfg.peerEndpoint, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (isConnecting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect) {
                    Text(if (isActive) "Disconnect" else "Connect")
                }
                androidx.compose.material3.IconButton(onClick = onDelete) { IconDelete() }
            }
            if (isActive) {
                Text("\u25CF Connected via gotatun (WireGuard)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Text("Address: ${cfg.address}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text("Allowed: ${cfg.peerAllowedIPs}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}
