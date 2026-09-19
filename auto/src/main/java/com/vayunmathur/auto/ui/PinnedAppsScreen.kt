package com.vayunmathur.auto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.auto.R
import com.vayunmathur.auto.data.PinnedAppsPrefs
import com.vayunmathur.auto.domain.PinnedAppRow
import com.vayunmathur.auto.domain.PinnedAppsViewModel
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconKeyboardArrowDown
import com.vayunmathur.library.ui.IconKeyboardArrowUp
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberMessenger

/**
 * The phone pin picker: every discovered car app with a check plus up/down
 * reorder. Pins cap at [PinnedAppsPrefs.MAX_PINNED]; overflow reports on the
 * shared snackbar host — never a Toast.
 */
@Composable
fun PinnedAppsScreen(
    onNavigateBack: () -> Unit,
    viewModel: PinnedAppsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val messenger = rememberMessenger()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val rejected by viewModel.rejected.collectAsStateWithLifecycle()
    LaunchedEffect(rejected) {
        if (rejected != null) {
            messenger.show(context.getString(R.string.pinned_apps_full, PinnedAppsPrefs.MAX_PINNED))
            viewModel.consumeRejected()
        }
    }
    val scrollBehavior = appBarScrollBehavior()
    AppScaffold(
        title = stringResource(R.string.pinned_apps_title),
        onNavigateBack = onNavigateBack,
        scrollBehavior = scrollBehavior,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.pinned_apps_body, PinnedAppsPrefs.MAX_PINNED),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            items(rows, key = { it.app.id }) { row ->
                PinnedAppRowCard(
                    row = row,
                    onToggle = { viewModel.toggle(row.app.id) },
                    onMoveUp = { viewModel.moveUp(row.app.id) },
                    onMoveDown = { viewModel.moveDown(row.app.id) },
                )
            }
        }
    }
}

@Composable
private fun PinnedAppRowCard(
    row: PinnedAppRow,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(row.app.label.toString()) },
            supportingContent = {
                row.rank?.let { Text(stringResource(R.string.pinned_apps_rank, it + 1)) }
            },
            leadingContent = {
                Checkbox(checked = row.rank != null, onCheckedChange = { onToggle() })
            },
            trailingContent = {
                if (row.rank != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onMoveUp) { IconKeyboardArrowUp() }
                        IconButton(onClick = onMoveDown) { IconKeyboardArrowDown() }
                    }
                }
            },
        )
    }
}
