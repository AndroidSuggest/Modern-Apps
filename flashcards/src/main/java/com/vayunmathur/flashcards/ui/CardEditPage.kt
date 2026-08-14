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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.flashcards.R
import com.vayunmathur.flashcards.Route
import com.vayunmathur.flashcards.data.Card
import com.vayunmathur.flashcards.util.CardEditActions
import com.vayunmathur.flashcards.util.CardEditUiState
import com.vayunmathur.flashcards.util.FlashcardsViewModel
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.MarkdownEditor
import com.vayunmathur.library.ui.MarkdownFormatToolbar
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack

/**
 * Binds the card with [cardId] (0 for a new card) in [deckId] to the stateless
 * [CardEditScreen]: holds the editable markdown/tags and saves via the ViewModel.
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
    var tagsText by remember { mutableStateOf("") }

    LaunchedEffect(dbCard) {
        dbCard?.let {
            frontText = it.front
            backText = it.back
            tagsText = it.tags
        }
    }

    val actions = object : CardEditActions {
        override fun back() { backStack.pop() }
        override fun setFront(front: String) { frontText = front }
        override fun setBack(back: String) { backText = back }
        override fun setTags(tags: String) { tagsText = tags }
        override fun save() {
            val card = dbCard?.copy(front = frontText, back = backText, tags = tagsText)
                ?: Card(deckId = deckId, front = frontText, back = backText, tags = tagsText)
            viewModel.upsertCard(card)
            backStack.pop()
        }
        override fun deleteCard() {
            dbCard?.let { viewModel.deleteCard(it) }
            backStack.pop()
        }
    }

    CardEditScreen(
        state = CardEditUiState(
            front = frontText,
            back = backText,
            tags = tagsText,
            isNew = cardId == 0L,
        ),
        actions = actions,
    )
}

/**
 * The card editor, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`. Front/back are markdown, edited
 * via [MarkdownEditor] with a shared [MarkdownFormatToolbar] for the focused field.
 */
@Composable
fun CardEditScreen(
    state: CardEditUiState,
    actions: CardEditActions,
) {
    var front by remember { mutableStateOf(TextFieldValue(state.front)) }
    var back by remember { mutableStateOf(TextFieldValue(state.back)) }
    var activeField by remember { mutableIntStateOf(-1) }

    LaunchedEffect(state.front) {
        if (state.front != front.text) front = TextFieldValue(state.front, TextRange(state.front.length))
    }
    LaunchedEffect(state.back) {
        if (state.back != back.text) back = TextFieldValue(state.back, TextRange(state.back.length))
    }

    AppScaffold(
        title = if (state.isNew) stringResource(R.string.new_card) else stringResource(R.string.edit_card),
        onNavigateBack = { actions.back() },
        actions = {
            if (!state.isNew) {
                IconButton(onClick = { actions.deleteCard() }) { IconDelete() }
            }
            IconButton(onClick = { actions.save() }) { IconSave() }
        },
        bottomBar = {
            when (activeField) {
                0 -> MarkdownFormatToolbar(value = front, onValueChange = { front = it; actions.setFront(it.text) })
                1 -> MarkdownFormatToolbar(value = back, onValueChange = { back = it; actions.setBack(it.text) })
            }
        },
    ) { paddingValues ->
        Column(
            Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(stringResource(R.string.front), style = com.vayunmathur.library.ui.MaterialTheme.typography.labelMedium)
            MarkdownEditor(
                value = front,
                onValueChange = { front = it; actions.setFront(it.text) },
                placeholder = stringResource(R.string.front),
                onFocusChanged = { if (it) activeField = 0 },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
            )
            Text(stringResource(R.string.back), style = com.vayunmathur.library.ui.MaterialTheme.typography.labelMedium)
            MarkdownEditor(
                value = back,
                onValueChange = { back = it; actions.setBack(it.text) },
                placeholder = stringResource(R.string.back),
                onFocusChanged = { if (it) activeField = 1 },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
            )
            OutlinedTextField(
                value = state.tags,
                onValueChange = { actions.setTags(it) },
                label = { Text(stringResource(R.string.tags)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}
