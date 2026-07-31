package com.vayunmathur.appstore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text

@Composable
fun SearchAndBrowsePage(
    viewModel: AppStoreViewModel,
    onAppClick: (UnifiedApp) -> Unit
) {
    val query by viewModel.searchQuery.collectAsState()
    val apps by viewModel.combinedBrowse.collectAsState()
    val installed by viewModel.installedApps.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val syncMsg by viewModel.syncMessage.collectAsState()
    val progressMap by viewModel.downloadProgress.collectAsState()

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.setSearch(it) },
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            placeholder = { Text("Search F-Droid & Play Store") },
            leadingIcon = { IconSearch() },
            trailingIcon = {
                if (query.isNotBlank()) {
                    IconButton(onClick = { viewModel.setSearch("") }) {
                        IconClose()
                    }
                }
            },
            singleLine = true
        )

        if (syncMsg.isNotBlank()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isSyncing) {
                    CircularProgressIndicator(Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(syncMsg, style = MaterialTheme.typography.labelSmall)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (apps.isEmpty() && query.isNotBlank()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("No results for \"$query\"", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else if (apps.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Welcome to App Store", style = MaterialTheme.typography.titleMedium)
                            Text("Combines F-Droid repos with Play Store listings", style = MaterialTheme.typography.bodySmall)
                            Text("Tap Sync in Repos to fetch F-Droid apps, or search for Play apps", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                items(apps, key = { it.packageName + it.source.name }) { app ->
                    val isInstalled = installed.any { it.packageName == app.packageName }
                    val progress = progressMap[app.packageName]
                    AppRow(app = app, isInstalled = isInstalled, progress = progress, onClick = { onAppClick(app) })
                }
            }
        }
    }
}
