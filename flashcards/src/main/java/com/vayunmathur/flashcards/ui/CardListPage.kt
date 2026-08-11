package com.vayunmathur.flashcards.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.flashcards.R
import com.vayunmathur.flashcards.Route
import com.vayunmathur.flashcards.data.Card
import com.vayunmathur.flashcards.util.CardListActions
import com.vayunmathur.flashcards.util.CardListUiState
import com.vayunmathur.flashcards.util.FlashcardsViewModel
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.NavBackStack

/** Binds the deck with [deckId] to the stateless [CardListScreen]. */
@Composable
fun CardListPage(
    backStack: NavBackStack<Route>,
    viewModel: FlashcardsViewModel,
    deckId: Long,
) {
    val decks by viewModel.decks.collectAsStateWithLifecycle()
    val cards by remember(deckId) { viewModel.cardsFor(deckId) }
        .collectAsStateWithLifecycle(emptyList())

    val deckName = decks.firstOrNull { it.id == deckId }?.name ?: ""
    val now = System.currentTimeMillis()

    val actions = remember(backStack, viewModel, deckId) {
        object : CardListActions {
            override fun back() { backStack.pop() }
            override fun openCard(id: Long) { backStack.add(Route.CardEdit(deckId, id)) }
            override fun addCard() { backStack.add(Route.CardEdit(deckId, 0)) }
            override fun deleteCard(card: Card) { viewModel.deleteCard(card) }
            override fun study() { backStack.add(Route.Review(deckId)) }
        }
    }

    CardListScreen(
        state = CardListUiState(
            deckName = deckName,
            cards = cards,
            dueCount = cards.count { it.dueDate <= now },
        ),
        actions = actions,
    )
}

/**
 * The card list for one deck, with no dependency on the ViewModel or the back stack so it
 * can be rendered from a `@Preview` — see `src/screenshotTest`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardListScreen(state: CardListUiState, actions: CardListActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.deckName) },
                navigationIcon = { IconNavigation { actions.back() } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { actions.addCard() }) {
                IconAdd()
            }
        },
    ) { paddingValues ->
        Column(Modifier.fillMaxSize().padding(paddingValues)) {
            if (state.dueCount > 0) {
                Button(
                    onClick = { actions.study() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.study))
                }
            }
            if (state.cards.isEmpty()) {
                Text(
                    stringResource(R.string.no_cards),
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.cards, key = { it.id }) { card ->
                        ListItem(
                            headlineContent = { Text(card.front.substringBefore('\n').take(60)) },
                            supportingContent = {
                                Text(card.back.substringBefore('\n').take(60))
                            },
                            trailingContent = {
                                IconButton(onClick = { actions.deleteCard(card) }) {
                                    IconDelete()
                                }
                            },
                            modifier = Modifier.clickable { actions.openCard(card.id) },
                        )
                    }
                }
            }
        }
    }
}
