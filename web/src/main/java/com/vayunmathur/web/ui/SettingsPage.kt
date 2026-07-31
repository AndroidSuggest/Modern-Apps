package com.vayunmathur.web.ui

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.RadioButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.web.Route
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.web.util.CacheMode
import com.vayunmathur.web.util.WebViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    viewModel: WebViewModel,
    backStack: NavBackStack<Route>,
) {
    val context = LocalContext.current
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showCacheDialog by remember { mutableStateOf(false) }

    val storageCount by viewModel.storageInfos.collectAsStateWithLifecycle()
    val permCount by viewModel.sitePermissions.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconNavigation(backStack) }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                Text("General", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }

            item {
                ListItem(
                    headlineContent = { Text("Search engine") },
                    supportingContent = { Text("DuckDuckGo (locked)") }
                )
            }

            item {
                ListItem(
                    headlineContent = { Text("Cache mode") },
                    supportingContent = { Text("${viewModel.cacheMode.title} — ${viewModel.cacheMode.description}") },
                    modifier = Modifier.clickable { showCacheDialog = true }
                )
            }

            item {
                ListItem(
                    headlineContent = { Text("JavaScript") },
                    supportingContent = { Text("Required by most sites") },
                    trailingContent = {
                        Switch(checked = viewModel.jsEnabled, onCheckedChange = { viewModel.updateJsEnabled(it) })
                    }
                )
            }

            item {
                ListItem(
                    headlineContent = { Text("Block third-party cookies") },
                    supportingContent = { Text("May break logins") },
                    trailingContent = {
                        Switch(checked = viewModel.blockThirdPartyCookies, onCheckedChange = { viewModel.updateBlockThirdParty(it) })
                    }
                )
            }

            item {
                ListItem(
                    headlineContent = { Text("Desktop mode") },
                    supportingContent = { Text("Request desktop site") },
                    trailingContent = {
                        Switch(checked = viewModel.desktopMode, onCheckedChange = { viewModel.updateDesktopMode(it) })
                    }
                )
            }

            item {
                ListItem(
                    headlineContent = { Text("Ad-tracker blocking") },
                    supportingContent = { Text("Blocks doubleclick, googletagmanager, analytics") },
                    trailingContent = {
                        Switch(checked = viewModel.adBlockEnabled, onCheckedChange = { viewModel.updateAdBlock(it) })
                    }
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

            item {
                Text("Privacy & data", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }

            item {
                ListItem(
                    headlineContent = { Text("Clear browsing data") },
                    supportingContent = { Text("Cookies, cache, history, storage, permissions") },
                    modifier = Modifier.clickable { showClearDataDialog = true }
                )
            }

            item {
                ListItem(
                    headlineContent = { Text("Site data") },
                    supportingContent = { Text("${storageCount.size} sites • ${permCount.size} permission grants") },
                    modifier = Modifier.clickable { backStack.add(Route.SiteData) }
                )
            }

            item {
                ListItem(
                    headlineContent = { Text("History") },
                    supportingContent = { Text("${viewModel.history.value.size} entries") },
                    modifier = Modifier.clickable { backStack.add(Route.History) }
                )
            }

            item {
                ListItem(
                    headlineContent = { Text("Downloads") },
                    supportingContent = { Text("${viewModel.downloads.value.size} files") },
                    modifier = Modifier.clickable { backStack.add(Route.Downloads) }
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

            item {
                Text("About", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }

            item {
                ListItem(
                    headlineContent = { Text("Web") },
                    supportingContent = { Text("Blank New Tab — url=\"\" / \"about:blank\", title \"New Tab\". Full address display in pill (no prettyUrl truncation), tap pill to expand full-page SearchBar with reload/clear full-width card below. Top bar is back/forward only left, tab chip + menu right. DuckDuckGo used only as search fallback for plain queries.") }
                )
            }

            item {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Engine: ${WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } ?: "System WebView"}", style = MaterialTheme.typography.bodySmall)
                        Text("NTP contract: BrowserTab(url=\"\"), SearchBar placeholder \"Search or enter address\", no DDG preload. Omnibox holds full URL.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (showCacheDialog) {
        AlertDialog(
            onDismissRequest = { showCacheDialog = false },
            title = { Text("Cache mode") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    CacheMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.updateCacheMode(mode); showCacheDialog = false
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = viewModel.cacheMode == mode, onClick = {
                                viewModel.updateCacheMode(mode); showCacheDialog = false
                            })
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(mode.title, style = MaterialTheme.typography.bodyMedium)
                                Text(mode.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCacheDialog = false }) { Text("Close") } }
        )
    }

    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = { Text("Clear browsing data?") },
            text = { Text("Clears cookies, cache, storage, history, downloads, permissions. Tabs stay open.") },
            confirmButton = {
                TextButton(onClick = {
                    try { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush() } catch (_: Exception) {}
                    try { WebStorage.getInstance().deleteAllData() } catch (_: Exception) {}
                    try { WebView(context).clearCache(true) } catch (_: Exception) {}
                    viewModel.clearHistory()
                    viewModel.clearAllDownloads()
                    viewModel.clearAllSiteData()
                    showClearDataDialog = false
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { showClearDataDialog = false }) { Text("Cancel") } }
        )
    }
}
