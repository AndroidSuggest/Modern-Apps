package com.vayunmathur.appstore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.util.AppStoreViewModel
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

@Composable
fun InstalledPage(
    viewModel: AppStoreViewModel,
    onAppClick: (UnifiedApp) -> Unit
) {
    val installed by viewModel.installedApps.collectAsState()
    val cached by viewModel.cachedApps.collectAsState()
    val icons by viewModel.installedIcons.collectAsState()

    val apps = installed.map { inst ->
        val cachedApp = cached.find { it.packageName == inst.packageName }
        UnifiedApp(
            packageName = inst.packageName,
            source = if (cachedApp != null) AppSource.FDROID else AppSource.PLAYSTORE,
            name = inst.name,
            summary = cachedApp?.summary ?: inst.versionName ?: "",
            description = cachedApp?.description ?: "",
            iconUrl = cachedApp?.iconUrl,
            author = cachedApp?.author,
            versionName = inst.versionName,
            versionCode = inst.versionCode,
            apkUrl = cachedApp?.apkUrl,
            repoUrl = cachedApp?.repoUrl
        )
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 24.dp, start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text("${installed.size} installed apps", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(4.dp))
            }
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

@Composable
fun UpdatesPage(viewModel: AppStoreViewModel, onAppClick: (UnifiedApp) -> Unit) {
    val installed by viewModel.installedApps.collectAsState()
    val cached by viewModel.cachedApps.collectAsState()
    val progressMap by viewModel.downloadProgress.collectAsState()
    val icons by viewModel.installedIcons.collectAsState()

    val updates = cached.mapNotNull { cachedEntity ->
        val inst = installed.find { it.packageName == cachedEntity.packageName } ?: return@mapNotNull null
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
                repoUrl = cachedEntity.repoUrl
            )
        } else null
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 24.dp, start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (updates.isEmpty()) {
                item {
                    Text("All apps up to date", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                }
            } else {
                item {
                    Text("${updates.size} updates available", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(4.dp))
                }
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
