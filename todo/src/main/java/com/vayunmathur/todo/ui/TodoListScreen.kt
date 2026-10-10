package com.vayunmathur.todo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.vayunmathur.library.room.SqlCipherDbCodec
import com.vayunmathur.library.ui.BackupButtons
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.todo.R
import com.vayunmathur.todo.data.todoDbConfigs
import com.vayunmathur.todo.platform.TodoListActions
import com.vayunmathur.todo.platform.TodoListUiState
import kotlin.time.Instant

/**
 * The todo list, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`, which is where the store listing
 * images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoListScreen(
    state: TodoListUiState,
    actions: TodoListActions,
    /** Top-bar backup/restore buttons; empty in a preview, which has no database. */
    backupButtons: @Composable RowScope.() -> Unit = {},
    /**
     * Seed for the screen's own UI-only search state. The app always takes the default;
     * previews set it so the search results can be captured without typing.
     */
    initialSearchQuery: String = "",
) {
    var searchQuery by remember { mutableStateOf(initialSearchQuery) }
    val filtered = remember(state.todos, searchQuery) {
        if (searchQuery.isBlank()) state.todos
        else state.todos.filter { it.title.contains(searchQuery, true) }
    }

    BackHandler(enabled = searchQuery.isNotEmpty()) {
        searchQuery = ""
    }

    val listState = rememberLazyListState()

    LazyListScaffold(
        topBar = {
            TopAppBar(
                title = {
                    CommonSearchBar(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = stringResource(R.string.app_name),
                        padding = PaddingValues(0.dp),
                    )
                },
                actions = backupButtons,
            )
        },
        floatingActionButton = {
            if (state.showAddButton) {
                FloatingActionButton(onClick = { actions.createTodo() }) {
                    IconAdd()
                }
            }
        },
        state = listState,
        scrollBehavior = appBarScrollBehavior(),
    ) {
        items(filtered, key = { it.id }) { todo ->
            ListItem(
                content = {
                    Text(todo.title.ifBlank { stringResource(R.string.untitled) })
                },
                modifier = Modifier.clickable { actions.openTodo(todo.id) },
                supportingContent = todo.dueAt?.let { due ->
                    {
                        Text(DateString.dateShort(Instant.fromEpochMilliseconds(due)))
                    }
                },
                leadingContent = {
                    Checkbox(
                        checked = todo.done,
                        onCheckedChange = { actions.toggleDone(todo) },
                    )
                },
            )
        }
    }
}

/** Binds the repository-backed config to the shared backup buttons. Used by [TodoListPage]. */
@Composable
fun TodoBackupButtons() {
    val context = LocalContext.current
    BackupButtons(
        dbConfigs = remember { todoDbConfigs(context) },
        dbCodec = SqlCipherDbCodec,
        extraFiles = emptyList(),
    )
}
