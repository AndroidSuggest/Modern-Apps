package com.vayunmathur.appstore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.util.AppStoreViewModel
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

@Composable
fun InstalledPage(
    viewModel: AppStoreViewModel,
    onAppClick: (UnifiedApp) -> Unit
) {
    val installed by viewModel.installedApps.collectAsState()
    val cached by viewModel.cachedApps.collectAsState()
    val favorites by viewModel.favorites.collectAsState()

    // Map installed to UnifiedApp for unified UI, using cached info when available
    val apps = installed.map { inst ->
        val cachedApp = cached.find { it.packageName == inst.packageName }
        UnifiedApp(
            packageName = inst.packageName,
            source = com.vayunmathur.appstore.data.AppSource.UNKNOWN,
            name = inst.name,
            versionName = inst.versionName,
            versionCode = inst.versionCode,
            iconUrl = cachedApp?.iconUrl
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("${installed.size} installed apps", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(4.dp))
        }
        items(apps, key = { it.packageName }) { app ->
            AppRow(app = app, isInstalled = true, progress = null, onClick = { onAppClick(app) })
        }
    }
}

@Composable
fun UpdatesPage(viewModel: AppStoreViewModel, onAppClick: (UnifiedApp) -> Unit) {
    val installed by viewModel.installedApps.collectAsState()
    val cached by viewModel.cachedApps.collectAsState()
    val progressMap by viewModel.downloadProgress.collectAsState()

    val updates = cached.mapNotNull { cachedEntity ->
        val inst = installed.find { it.packageName == cachedEntity.packageName } ?: return@mapNotNull null
        if (cachedEntity.versionCode > inst.versionCode) {
            com.vayunmathur.appstore.data.UnifiedApp(
                packageName = cachedEntity.packageName,
                source = try { com.vayunmathur.appstore.data.AppSource.valueOf(cachedEntity.source) } catch (_: Exception) { com.vayunmathur.appstore.data.AppSource.FDROID },
                name = cachedEntity.name,
                summary = cachedEntity.summary,
                description = cachedEntity.description,
                iconUrl = cachedEntity.iconUrl,
                versionName = cachedEntity.versionName,
                versionCode = cachedEntity.versionCode,
                sizeBytes = cachedEntity.sizeBytes,
                apkUrl = cachedEntity.apkUrl,
                repoUrl = cachedEntity.repoUrl
            )
        } else null
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (updates.isEmpty()) {
            item {
                Text("All apps up to date", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            }
        } else {
            item {
                Text("${updates.size} updates available", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(4.dp))
            }
            items(updates, key = { it.packageName }) { app ->
                AppRow(app = app, isInstalled = true, progress = progressMap[app.packageName], onClick = { onAppClick(app) })
            }
        }
    }
}

@Composable
fun FavoritesPage(viewModel: AppStoreViewModel, onAppClick: (UnifiedApp) -> Unit) {
    val favorites by viewModel.favorites.collectAsState()
    val cached by viewModel.cachedApps.collectAsState()
    val installed by viewModel.installedApps.collectAsState()

    val apps = favorites.mapNotNull { fav ->
        cached.find { it.packageName == fav.packageName }?.let { c ->
            com.vayunmathur.appstore.data.UnifiedApp(
                packageName = c.packageName,
                source = try { com.vayunmathur.appstore.data.AppSource.valueOf(c.source) } catch (_: Exception) { com.vayunmathur.appstore.data.AppSource.FDROID },
                name = c.name,
                summary = c.summary,
                iconUrl = c.iconUrl,
                versionName = c.versionName,
                versionCode = c.versionCode,
                apkUrl = c.apkUrl
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (apps.isEmpty()) {
            item { Text("No favorites yet", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp)) }
        } else {
            items(apps, key = { it.packageName }) { app ->
                val isInstalled = installed.any { it.packageName == app.packageName }
                AppRow(app = app, isInstalled = isInstalled, progress = null, onClick = { onAppClick(app) })
            }
        }
    }
}
