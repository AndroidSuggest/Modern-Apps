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
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.RadioButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.web.Route
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.web.util.SearchEngine
import com.vayunmathur.web.util.WebViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    viewModel: WebViewModel,
    backStack: NavBackStack<Route>,
) {
    val context = LocalContext.current
    var showSearchEngineDialog by remember { mutableStateOf(false) }
    var showHomepageDialog by remember { mutableStateOf(false) }
    var homepageDraft by remember { mutableStateOf(viewModel.homepage) }
    var showClearDataDialog by remember { mutableStateOf(false) }

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
                Text(
                    "General",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            item {
                ListItem(
                    headlineContent = { Text("Search engine") },
                    supportingContent = { Text(viewModel.searchEngine.displayName) },
                    modifier = Modifier.clickable { showSearchEngineDialog = true }
                )
            }

            item {
                ListItem(
                    headlineContent = { Text("Homepage") },
                    supportingContent = { Text(viewModel.homepage) },
                    modifier = Modifier.clickable {
                        homepageDraft = viewModel.homepage
                        showHomepageDialog = true
                    }
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

            item {
                Text(
                    "Privacy",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            item {
                ListItem(
                    headlineContent = { Text("Clear browsing data") },
                    supportingContent = { Text("Cookies, cache, history, storage") },
                    modifier = Modifier.clickable { showClearDataDialog = true }
                )
            }

            item {
                ListItem(
                    headlineContent = { Text("History") },
                    supportingContent = { Text("${viewModel.history.value.size} entries") },
                    modifier = Modifier.clickable { backStack.add(Route.History) }
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

            item {
                Text(
                    "About",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            item {
                ListItem(
                    headlineContent = { Text("Web") },
                    supportingContent = { Text("System WebView browser with tabs, bookmarks, history and private mode. Your data stays on-device.") }
                )
            }

            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Engine: ${WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } ?: "System WebView"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Private tabs are never saved to history and discarded when closed. Tabs are restored on next launch (except private).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showSearchEngineDialog) {
        AlertDialog(
            onDismissRequest = { showSearchEngineDialog = false },
            title = { Text("Search engine") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SearchEngine.entries.forEach { engine ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.updateSearchEngine(engine)
                                    showSearchEngineDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = viewModel.searchEngine == engine,
                                onClick = {
                                    viewModel.updateSearchEngine(engine)
                                    showSearchEngineDialog = false
                                }
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(engine.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text(engine.homepage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSearchEngineDialog = false }) { Text("Close") }
            }
        )
    }

    if (showHomepageDialog) {
        AlertDialog(
            onDismissRequest = { showHomepageDialog = false },
            title = { Text("Homepage") },
            text = {
                OutlinedTextField(
                    value = homepageDraft,
                    onValueChange = { homepageDraft = it },
                    placeholder = { Text("https://...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (homepageDraft.isNotBlank()) viewModel.updateHomepage(homepageDraft.trim())
                    showHomepageDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showHomepageDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = { Text("Clear browsing data?") },
            text = { Text("This will clear cookies, cache, local storage, and history. Open tabs will not be closed.") },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                    } catch (_: Exception) {}
                    try { WebStorage.getInstance().deleteAllData() } catch (_: Exception) {}
                    try { WebView(context).clearCache(true) } catch (_: Exception) {}
                    viewModel.clearHistory()
                    showClearDataDialog = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataDialog = false }) { Text("Cancel") }
            }
        )
    }
}
