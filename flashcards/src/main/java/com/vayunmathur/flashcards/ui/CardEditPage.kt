package com.vayunmathur.flashcards.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.flashcards.R
import com.vayunmathur.flashcards.Route
import com.vayunmathur.flashcards.data.Card
import com.vayunmathur.flashcards.util.CardEditActions
import com.vayunmathur.flashcards.util.CardEditUiState
import com.vayunmathur.flashcards.util.FlashcardsViewModel
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.NavBackStack

/**
 * Binds the card with [cardId] (0 for a new card) in [deckId] to the stateless
 * [CardEditScreen]: holds the editable front/back text and saves via the ViewModel.
 */
@Composable
fun CardEditPage(
    backStack: NavBackStack<Route>,
    viewModel: FlashcardsViewModel,
    deckId: Long,
    cardId: Long,
) {
    val dbCard by remember(cardId) { viewModel.cardById(cardId) }
        .collectAsStateWithLifecycle(null)

    var frontText by remember { mutableStateOf("") }
    var backText by remember { mutableStateOf("") }

    LaunchedEffect(dbCard) {
        dbCard?.let {
            frontText = it.front
            backText = it.back
        }
    }

    val actions = object : CardEditActions {
        override fun back() { backStack.pop() }
        override fun setFront(front: String) { frontText = front }
        override fun setBack(back: String) { backText = back }
        override fun save() {
            val card = dbCard?.copy(front = frontText, back = backText)
                ?: Card(deckId = deckId, front = frontText, back = backText)
            viewModel.upsertCard(card)
            backStack.pop()
        }
        override fun deleteCard() {
            dbCard?.let { viewModel.deleteCard(it) }
            backStack.pop()
        }
    }

    CardEditScreen(
        state = CardEditUiState(front = frontText, back = backText, isNew = cardId == 0L),
        actions = actions,
    )
}

/**
 * The card editor, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`. Edits are hoisted through
 * [CardEditActions]; a preview supplies [CardEditActions.Noop].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardEditScreen(
    state: CardEditUiState,
    actions: CardEditActions,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconNavigation { actions.back() } },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = { actions.deleteCard() }) { IconDelete() }
                    }
                    IconButton(onClick = { actions.save() }) { IconSave() }
                },
            )
        },
    ) { paddingValues ->
        Column(
            Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = state.front,
                onValueChange = { actions.setFront(it) },
                label = { Text(stringResource(R.string.front)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = state.back,
                onValueChange = { actions.setBack(it) },
                label = { Text(stringResource(R.string.back)) },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
        }
    }
}
