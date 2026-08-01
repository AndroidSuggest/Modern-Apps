package com.vayunmathur.vpn.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.Canvas
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.vpn.R
import com.vayunmathur.vpn.Route
import com.vayunmathur.vpn.util.BypassList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One selectable row: an installed app the user can route around the tunnel. */
private data class BypassApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
)

/**
 * Per-app split tunnelling. Anything switched on here is passed to
 * `VpnService.Builder.addDisallowedApplication` next time the tunnel is established.
 *
 * The list only offers apps that hold INTERNET — excluding the rest keeps it to apps where
 * the choice actually means something — and never offers this app itself, since bypassing
 * our own traffic has no useful meaning here.
 */
@Composable
fun BypassListPage(backStack: NavBackStack<Route>) {
    val context = LocalContext.current
    val bypassed by BypassList.flow(context).collectAsState(initial = emptySet())

    // PackageManager queries are slow enough to jank the first frame; do them off the main
    // thread and show a spinner until they land.
    val apps by produceState<List<BypassApp>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { loadApps(context.packageManager, context.packageName) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bypass_list_title)) },
                navigationIcon = { IconNavigation(backStack) },
            )
        },
    ) { pad ->
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        stringResource(R.string.bypass_loading_apps),
                        Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            return@Scaffold
        }

        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item {
                LockdownWarning()
            }
            item {
                Text(
                    stringResource(R.string.bypass_selected_count, bypassed.size),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(list, key = { it.packageName }) { app ->
                val isBypassed = app.packageName in bypassed
                ListItem(
                    modifier = Modifier.fillMaxWidth(),
                    leadingContent = { AppIconImage(app.icon) },
                    trailingContent = {
                        Switch(
                            checked = isBypassed,
                            onCheckedChange = { BypassList.setBypassed(context, app.packageName, it) },
                        )
                    },
                    supportingContent = { Text(app.packageName) },
                    content = { Text(app.label) },
                )
            }
        }
    }
}

/**
 * Android has no public API for reading the "Block connections without VPN" flag, so this
 * states the interaction plainly instead of trying to detect it.
 */
@Composable
private fun LockdownWarning() {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.bypass_lockdown_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(onClick = { openVpnSettings(context) }) {
                Text(stringResource(R.string.open_system_vpn_settings))
            }
        }
    }
}

/** Draws a PackageManager Drawable without pulling in an image-loading dependency. */
@Composable
private fun AppIconImage(icon: Drawable?) {
    if (icon == null) {
        Box(Modifier.size(40.dp))
        return
    }
    Canvas(Modifier.size(40.dp)) {
        drawIntoCanvas { canvas ->
            icon.setBounds(0, 0, size.width.toInt(), size.height.toInt())
            icon.draw(canvas.nativeCanvas)
        }
    }
}

private fun loadApps(pm: PackageManager, selfPackage: String): List<BypassApp> {
    val installed = runCatching {
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
    }.getOrDefault(emptyList())

    return installed
        .asSequence()
        .filter { it.packageName != selfPackage }
        // Only apps that can actually use the network; the rest would be noise.
        .filter { pm.checkPermission(android.Manifest.permission.INTERNET, it.packageName) == PackageManager.PERMISSION_GRANTED }
        .map { info: ApplicationInfo ->
            BypassApp(
                packageName = info.packageName,
                label = runCatching { pm.getApplicationLabel(info).toString() }
                    .getOrDefault(info.packageName),
                icon = runCatching { pm.getApplicationIcon(info) }.getOrNull(),
            )
        }
        .sortedBy { it.label.lowercase() }
        .toList()
}

internal fun openVpnSettings(context: android.content.Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (_: Exception) {}
    }
}
