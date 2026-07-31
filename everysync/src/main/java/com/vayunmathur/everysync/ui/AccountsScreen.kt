package com.vayunmathur.everysync.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconProvider
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.everysync.R
import com.vayunmathur.everysync.Route
import com.vayunmathur.everysync.provider.ProviderRegistry
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.library.util.NavBackStack
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(backStack: NavBackStack<Route>, viewModel: EverySyncViewModel) {
    val permissions = arrayOf(
        android.Manifest.permission.READ_CONTACTS,
        android.Manifest.permission.WRITE_CONTACTS,
        android.Manifest.permission.READ_CALENDAR,
        android.Manifest.permission.WRITE_CALENDAR,
    )
    PermissionsChecker(permissions, stringResource(R.string.need_permissions)) {
        val accounts by viewModel.accounts.collectAsStateWithLifecycle()
        val syncing by viewModel.syncing.collectAsStateWithLifecycle()
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.accounts_title)) },
                    actions = {
                        IconButton(onClick = { backStack.add(Route.Settings) }) {
                            IconSettings()
                        }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { backStack.add(Route.AddAccount) }) {
                    IconAdd()
                }
            },
        ) { padding ->
            if (accounts.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.no_accounts),
                        Modifier.padding(32.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(Modifier.padding(padding)) {
                    items(accounts, key = { it.accountName }) { account ->
                        val provider = ProviderRegistry.get(account.providerId)
                        val isSyncing = account.accountName in syncing
                        ListItem(
                            modifier = Modifier.clickable { backStack.add(Route.AccountDetail(account.accountName)) },
                            content = { Text(account.accountName) },
                            supportingContent = {
                                Column {
                                    Text(provider?.displayName ?: account.providerId)
                                    Text(
                                        when {
                                            isSyncing -> stringResource(R.string.syncing)
                                            account.lastSyncError != null -> account.lastSyncError
                                            account.lastSyncEpochMs > 0 ->
                                                stringResource(R.string.last_synced, formatTime(LocalContext.current, account.lastSyncEpochMs))
                                            else -> stringResource(R.string.never_synced)
                                        },
                                    )
                                }
                            },
                            leadingContent = {
                                (provider?.icon ?: { IconProvider() })()
                            },
                            trailingContent = {
                                if (isSyncing) {
                                    CircularProgressIndicator(Modifier.size(24.dp))
                                } else {
                                    IconButton(onClick = { viewModel.syncNow(account.accountName) }) {
                                        IconRefresh()
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun formatTime(context: Context, millis: Long): String {
    val date = Date(millis)
    // android.text.format honours the user's 24-hour setting; java.text only follows the locale.
    return DateFormat.getDateFormat(context).format(date) + " " +
        DateFormat.getTimeFormat(context).format(date)
}
