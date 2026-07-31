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
import androidx.compose.foundation.lazy.LazyColumn
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
import com.vayunmathur.appstore.data.DefaultRepos
import com.vayunmathur.appstore.util.AppStoreViewModel
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.IconButton

@Composable
fun ReposPage(viewModel: AppStoreViewModel) {
    val repos by viewModel.repos.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var newUrl by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                IconAdd()
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("F-Droid Repositories", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Add F-Droid compatible repos. Play Store listings are always enabled and searched live.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.syncRepos() }, enabled = !isSyncing, modifier = Modifier.weight(1f)) {
                        Text(if (isSyncing) "Syncing..." else "Sync All")
                    }
                    Button(onClick = { viewModel.loadTopCharts() }, modifier = Modifier.weight(1f)) {
                        Text("Refresh Play")
                    }
                }
            }
            items(repos, key = { it.url }) { repo ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(repo.name, style = MaterialTheme.typography.titleSmall)
                                Text(repo.url, style = MaterialTheme.typography.labelSmall)
                                if (repo.lastSync > 0) {
                                    Text("Last sync: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(repo.lastSync))}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Switch(checked = repo.enabled, onCheckedChange = { viewModel.toggleRepo(repo.url) })
                            IconButton(onClick = { viewModel.deleteRepo(repo.url) }) {
                                IconDelete()
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add Repository") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newUrl, onValueChange = { newUrl = it }, label = { Text("Repo URL") }, placeholder = { Text(DefaultRepos.FDROID_MAIN) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Name (optional)") }, modifier = Modifier.fillMaxWidth())
                    Text("Examples:\n${DefaultRepos.FDROID_MAIN}\n${DefaultRepos.IZVYZID}", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newUrl.isNotBlank()) {
                        viewModel.addRepo(newUrl, newName)
                        newUrl = ""
                        newName = ""
                        showAdd = false
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } }
        )
    }
}
