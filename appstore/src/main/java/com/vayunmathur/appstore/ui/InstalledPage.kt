package com.vayunmathur.appstore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.util.AppStoreViewModel
import com.vayunmathur.appstore.util.InstalledFilter
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar

@Composable
fun InstalledPage(
    viewModel: AppStoreViewModel,
    onAppClick: (UnifiedApp) -> Unit
) {
    val cached by viewModel.cachedApps.collectAsState()
    val icons by viewModel.installedIcons.collectAsState()
    val srcMap by viewModel.installedSourceMap.collectAsState()
    val filteredInstalled by viewModel.filteredInstalled.collectAsState()
    val filter by viewModel.installedFilter.collectAsState()
    var confirmPkg by remember { mutableStateOf<String?>(null) }

    val allCount = srcMap.size
    val fdroidCount = srcMap.values.count { it == AppSource.FDROID }
    val playCount = srcMap.values.count { it == AppSource.PLAYSTORE }

    val apps = filteredInstalled.map { inst ->
        val cachedApp = cached.find { it.packageName == inst.packageName }
        val src = srcMap[inst.packageName] ?: AppSource.PLAYSTORE
        UnifiedApp(
            packageName = inst.packageName,
            source = src,
            name = inst.name,
            summary = cachedApp?.summary ?: inst.versionName ?: "",
            description = cachedApp?.description ?: "",
            iconUrl = cachedApp?.iconUrl,
            author = cachedApp?.author,
            versionName = inst.versionName,
            versionCode = inst.versionCode,
            apkUrl = cachedApp?.apkUrl,
            targetSdk = cachedApp?.targetSdk,
            repoUrl = cachedApp?.repoUrl
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Installed (${filteredInstalled.size})") }) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    FilterChip(
                        selected = filter == InstalledFilter.ALL,
                        onClick = { viewModel.setInstalledFilter(InstalledFilter.ALL) },
                        label = { Text("All ($allCount)") }
                    )
                }
                item {
                    FilterChip(
                        selected = filter == InstalledFilter.FDROID,
                        onClick = { viewModel.setInstalledFilter(InstalledFilter.FDROID) },
                        label = { Text("F-Droid ($fdroidCount)") }
                    )
                }
                item {
                    FilterChip(
                        selected = filter == InstalledFilter.PLAYSTORE,
                        onClick = { viewModel.setInstalledFilter(InstalledFilter.PLAYSTORE) },
                        label = { Text("Play Store ($playCount)") }
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (apps.isEmpty()) {
                    item {
                        Text(
                            if (allCount == 0) "No installed apps found in F-Droid or Play Store. Sync repos to populate F-Droid."
                            else "No apps match this filter",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    items(apps, key = { it.packageName }) { app ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                                AppRow(
                                    app = app,
                                    isInstalled = true,
                                    progress = null,
                                    installedIcon = icons[app.packageName],
                                    onClick = { onAppClick(app) }
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            IconButton(onClick = { confirmPkg = app.packageName }) { IconDelete() }
                        }
                    }
                }
            }
        }
    }

    confirmPkg?.let { pkg ->
        AlertDialog(
            onDismissRequest = { confirmPkg = null },
            title = { Text("Uninstall?") },
            text = { Text("Uninstall $pkg? You can reinstall from the store.") },
            confirmButton = {
                Button(onClick = {
                    confirmPkg = null
                    viewModel.uninstallApp(pkg)
                }) { Text("Uninstall") }
            },
            dismissButton = {
                TextButton(onClick = { confirmPkg = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun UpdatesPage(viewModel: AppStoreViewModel, onAppClick: (UnifiedApp) -> Unit) {
    val progressMap by viewModel.downloadProgress.collectAsState()
    val icons by viewModel.installedIcons.collectAsState()
    val combinedUpdates by viewModel.combinedUpdates.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val syncMsg by viewModel.syncMessage.collectAsState()
    val playUpdatesRaw by viewModel.playUpdates.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text("Updates") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (syncMsg.isNotBlank()) {
                Text(syncMsg, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(12.dp))
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.syncRepos() }, enabled = !isSyncing) {
                    Text("Sync F-Droid")
                }
                Button(onClick = { viewModel.syncPlayUpdates() }) {
                    Text("Check Play")
                }
                if (combinedUpdates.isNotEmpty()) {
                    Button(onClick = { viewModel.updateAll() }) {
                        Text("Update All (${combinedUpdates.size})")
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (combinedUpdates.isEmpty()) {
                    item { Text("All apps up to date", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp)) }
                    if (playUpdatesRaw.isEmpty()) {
                        item { Text("Tap Check Play to look for Play Store updates (requires anonymous auth)", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp)) }
                    }
                } else {
                    item { Text("${combinedUpdates.size} updates", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(4.dp)) }
                    items(combinedUpdates, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            isInstalled = true,
                            progress = progressMap[app.packageName],
                            installedIcon = icons[app.packageName],
                            onClick = { onAppClick(app) }
                        )
                    }
                }
            }
        }
    }
}
