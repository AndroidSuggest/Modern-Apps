package com.vayunmathur.web.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.web.Route
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.web.util.WebViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsPage(
    viewModel: WebViewModel,
    backStack: NavBackStack<Route>,
) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = { IconNavigation(backStack) },
                actions = {
                    if (downloads.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearAllDownloads() }) { IconDelete() }
                    }
                }
            )
        }
    ) { paddingValues ->
        if (downloads.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Text("No downloads", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(paddingValues), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(downloads, key = { it.id }) { dl ->
                    ListItem(
                        headlineContent = { Text(dl.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Column {
                                Text(dl.url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                Text("${dl.mimeType ?: ""} • ${formatTime(dl.startedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        leadingContent = { IconDownload() }
                    )
                }
            }
        }
    }
}

private fun formatTime(millis: Long): String {
    return try { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis)) } catch (_: Exception) { "" }
}
