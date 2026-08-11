package com.vayunmathur.flashcards.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.flashcards.R
import com.vayunmathur.flashcards.Route
import com.vayunmathur.flashcards.data.Deck
import com.vayunmathur.flashcards.data.flashcardsDbConfigs
import com.vayunmathur.flashcards.util.DeckListActions
import com.vayunmathur.flashcards.util.DeckListUiState
import com.vayunmathur.flashcards.util.DeckSummary
import com.vayunmathur.flashcards.util.FlashcardsViewModel
import com.vayunmathur.library.room.SqlCipherDbCodec
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.BackupButtons
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.NavBackStack

/** Binds [FlashcardsViewModel] and the nav back stack to the stateless [DeckListScreen]. */
@Composable
fun DeckListPage(backStack: NavBackStack<Route>, viewModel: FlashcardsViewModel) {
    val context = LocalContext.current
    val decks by viewModel.decks.collectAsStateWithLifecycle()
    val cards by viewModel.cards.collectAsStateWithLifecycle()

    val now = System.currentTimeMillis()
    val summaries = decks.map { deck ->
        val deckCards = cards.filter { it.deckId == deck.id }
        DeckSummary(
            deck = deck,
            dueCount = deckCards.count { it.dueDate <= now },
            totalCount = deckCards.size,
        )
    }

    val actions = remember(backStack, viewModel) {
        object : DeckListActions {
            override fun openDeck(id: Long) { backStack.add(Route.CardList(id)) }
            override fun addDeck(name: String) { viewModel.upsertDeck(Deck(name = name)) }
            override fun deleteDeck(deck: Deck) { viewModel.deleteDeck(deck) }
            override fun startReview(deckId: Long) { backStack.add(Route.Review(deckId)) }
        }
    }

    DeckListScreen(
        state = DeckListUiState(decks = summaries),
        actions = actions,
        backupButtons = {
            BackupButtons(
                dbConfigs = remember { flashcardsDbConfigs(context) },
                dbCodec = SqlCipherDbCodec,
                extraFiles = emptyList(),
            )
        },
    )
}

/**
 * The deck list, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`, which is where the store listing
 * images come from.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DeckListScreen(
    state: DeckListUiState,
    actions: DeckListActions,
    /** Top-bar backup/restore buttons; empty in a preview, which has no database. */
    backupButtons: @Composable RowScope.() -> Unit = {},
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Deck?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = backupButtons,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                IconAdd()
            }
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = paddingValues,
        ) {
            items(state.decks, key = { it.deck.id }) { summary ->
                ListItem(
                    headlineContent = { Text(summary.deck.name) },
                    supportingContent = {
                        Text(
                            stringResource(
                                R.string.deck_summary,
                                summary.dueCount,
                                summary.totalCount,
                            ),
                        )
                    },
                    trailingContent = {
                        if (summary.dueCount > 0) {
                            IconButton(onClick = { actions.startReview(summary.deck.id) }) {
                                IconPlay()
                            }
                        }
                    },
                    modifier = Modifier.combinedClickable(
                        onClick = { actions.openDeck(summary.deck.id) },
                        onLongClick = { pendingDelete = summary.deck },
                    ),
                )
            }
        }
    }

    if (showAddDialog) {
        AddDeckDialog(
            onAdd = { actions.addDeck(it) },
            onDismiss = { showAddDialog = false },
        )
    }

    pendingDelete?.let { deck ->
        ConfirmDialog(
            title = stringResource(R.string.delete),
            message = stringResource(R.string.delete_deck_message, deck.name),
            confirmLabel = stringResource(R.string.delete),
            dismissLabel = stringResource(R.string.cancel),
            destructive = true,
            onConfirm = {
                actions.deleteDeck(deck)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDeckDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    BackHandler { onDismiss() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_deck)) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.deck_name)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) onAdd(name.trim())
                onDismiss()
            }) { Text(stringResource(R.string.add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
