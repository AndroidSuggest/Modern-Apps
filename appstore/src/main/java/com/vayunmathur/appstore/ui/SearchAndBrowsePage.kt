package com.vayunmathur.appstore.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.appstore.R
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
import com.vayunmathur.appstore.util.BrowseActions
import com.vayunmathur.appstore.util.BrowseUiState
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.appstore.Route

/** Binds [AppStoreViewModel] to the stateless [SearchAndBrowseScreen]. */
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
    val icons by viewModel.installedIcons.collectAsState()

    SearchAndBrowseScreen(
        state = BrowseUiState(
            query = query,
            apps = apps,
            installedPackages = installed.map { it.packageName }.toSet(),
            downloadProgress = progressMap,
            installedIcons = icons,
            syncMessage = syncMsg,
            isSyncing = isSyncing,
        ),
        actions = viewModel,
        onAppClick = onAppClick,
    )
}

/**
 * The browse/search screen, with no dependency on the ViewModel so it can be rendered from
 * a `@Preview` — see `src/screenshotTest`, which is where the store listing images come
 * from.
 */
@Composable
fun SearchAndBrowseScreen(
    state: BrowseUiState,
    actions: BrowseActions,
    onAppClick: (UnifiedApp) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = { actions.setSearch(it) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.search_f_droid_play_store)) },
                leadingIcon = { IconSearch() },
                trailingIcon = {
                    if (state.query.isNotBlank()) {
                        IconButton(onClick = { actions.setSearch("") }) { IconClose() }
                    }
                },
                singleLine = true
            )

            if (state.syncMessage.isNotBlank()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.isSyncing) {
                        CircularProgressIndicator(Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(state.syncMessage, style = MaterialTheme.typography.labelSmall)
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.apps.isEmpty() && state.query.isNotBlank()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.no_results_for, state.query), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else if (state.apps.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.welcome_to_app_store), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.combines_f_droid_repos_with_play_store_l), style = MaterialTheme.typography.bodySmall)
                                Text(stringResource(R.string.tap_sync_in_repos_to_fetch_f_droid_apps), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } else {
                    items(state.apps, key = { it.packageName + it.source.name }) { app ->
                        AppRow(
                            app = app,
                            isInstalled = app.packageName in state.installedPackages,
                            progress = state.downloadProgress[app.packageName],
                            // No `isInstalled` guard: the icon map is built from the same
                            // PackageManager scan as the installed list, so it can only
                            // ever hold installed packages anyway — and without the guard
                            // a preview can hand every row an icon without also having to
                            // claim it is installed.
                            installedIcon = state.installedIcons[app.packageName],
                            onClick = { onAppClick(app) }
                        )
                    }
                }
            }
        }
    }
}
