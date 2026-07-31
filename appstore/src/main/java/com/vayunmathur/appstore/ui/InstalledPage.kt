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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.util.AppStoreViewModel
import com.vayunmathur.appstore.util.InstalledFilter
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
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

    // counts for filter chips: Only user apps that are present in either store
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
            // Filter chips with counts: All / F-Droid (n) / Play Store (n)
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
                        AppRow(
                            app = app,
                            isInstalled = true,
                            progress = null,
                            installedIcon = icons[app.packageName],
                            onClick = { onAppClick(app) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun UpdatesPage(viewModel: AppStoreViewModel, onAppClick: (UnifiedApp) -> Unit) {
    val installed by viewModel.installedApps.collectAsState()
    val cached by viewModel.cachedApps.collectAsState()
    val progressMap by viewModel.downloadProgress.collectAsState()
    val icons by viewModel.installedIcons.collectAsState()
    val srcMap by viewModel.installedSourceMap.collectAsState()

    // Only user apps present in stores, with F-Droid updates (targetSdk already filtered)
    val updates = cached.mapNotNull { cachedEntity ->
        val inst = installed.find { it.packageName == cachedEntity.packageName } ?: return@mapNotNull null
        if (srcMap[inst.packageName] == null) return@mapNotNull null
        if (cachedEntity.versionCode > inst.versionCode) {
            UnifiedApp(
                packageName = cachedEntity.packageName,
                source = AppSource.FDROID,
                name = cachedEntity.name,
                summary = cachedEntity.summary,
                description = cachedEntity.description,
                iconUrl = cachedEntity.iconUrl,
                author = cachedEntity.author,
                versionName = cachedEntity.versionName,
                versionCode = cachedEntity.versionCode,
                sizeBytes = cachedEntity.sizeBytes,
                apkUrl = cachedEntity.apkUrl,
                targetSdk = cachedEntity.targetSdk,
                repoUrl = cachedEntity.repoUrl
            )
        } else null
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Updates") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (updates.isEmpty()) {
                    item { Text("All apps up to date", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp)) }
                } else {
                    item { Text("${updates.size} updates", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(4.dp)) }
                    items(updates, key = { it.packageName }) { app ->
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
