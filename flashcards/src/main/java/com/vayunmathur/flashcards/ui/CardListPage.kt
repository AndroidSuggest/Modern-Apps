package com.vayunmathur.flashcards.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.flashcards.R
import com.vayunmathur.flashcards.Route
import com.vayunmathur.flashcards.data.Card
import com.vayunmathur.flashcards.util.CardListActions
import com.vayunmathur.flashcards.util.CardListUiState
import com.vayunmathur.flashcards.util.FlashcardsViewModel
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconDragHandle
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconUpload
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ReorderableItem
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberReorderableLazyListState
import com.vayunmathur.library.ui.reorderDragHandle
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.parseMarkdown

/** Binds the deck with [deckId] to the stateless [CardListScreen]. */
@Composable
fun CardListPage(
    backStack: NavBackStack<Route>,
    viewModel: FlashcardsViewModel,
    deckId: Long,
) {
    val context = LocalContext.current
    val decks by viewModel.decks.collectAsStateWithLifecycle()
    val cards by remember(deckId) { viewModel.cardsFor(deckId) }
        .collectAsStateWithLifecycle(emptyList())

    val deckName = decks.firstOrNull { it.id == deckId }?.name ?: ""
    val now = System.currentTimeMillis()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importCsv(deckId, it) } }

    LaunchedEffect(viewModel, deckId) {
        viewModel.shareRequests.collect { uri ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.share_deck)),
            )
        }
    }

    val actions = remember(backStack, viewModel, deckId) {
        object : CardListActions {
            override fun back() { backStack.pop() }
            override fun openCard(id: Long) { backStack.add(Route.CardEdit(deckId, id)) }
            override fun addCard() { backStack.add(Route.CardEdit(deckId, 0)) }
            override fun deleteCard(card: Card) { viewModel.deleteCard(card) }
            override fun study() { backStack.add(Route.Review(deckId)) }
            override fun reorder(cards: List<Card>) { viewModel.reorderCards(cards) }
            override fun openStats() { backStack.add(Route.Stats) }
            override fun share() { viewModel.exportDeck(deckId) }
        }
    }

    CardListScreen(
        state = CardListUiState(
            deckName = deckName,
            cards = cards.sortedBy { it.position },
            dueCount = cards.count { (!it.isNew && it.dueDate <= now) || it.isNew },
        ),
        actions = actions,
        onImport = { importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain")) },
    )
}

/**
 * The card list for one deck, with no dependency on the ViewModel or the back stack so it
 * can be rendered from a `@Preview` — see `src/screenshotTest`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CardListScreen(
    state: CardListUiState,
    actions: CardListActions,
    onImport: () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    val filtered = if (query.isBlank()) {
        state.cards
    } else {
        state.cards.filter {
            it.front.contains(query, true) || it.back.contains(query, true) ||
                it.tags.contains(query, true)
        }
    }

    AppScaffold(
        title = state.deckName,
        onNavigateBack = { actions.back() },
        actions = {
            IconButton(onClick = onImport) { IconUpload() }
            IconButton(onClick = { actions.share() }) { IconShare() }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { actions.addCard() }) { IconAdd() }
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
            if (state.cards.isNotEmpty()) {
                CommonSearchBar(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.search_cards),
                    padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            when {
                state.cards.isEmpty() -> EmptyState(
                    title = stringResource(R.string.no_cards),
                    message = stringResource(R.string.no_cards_hint),
                    icon = { IconAdd() },
                    modifier = Modifier.fillMaxSize(),
                )
                query.isBlank() -> ReorderableCardList(filtered, actions)
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { card ->
                        CardRow(card, onOpen = { actions.openCard(card.id) }, onDelete = { actions.deleteCard(card) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReorderableCardList(cards: List<Card>, actions: CardListActions) {
    val listState = rememberLazyListState()
    var local by remember { mutableStateOf(cards) }
    var hasDragged by remember { mutableStateOf(false) }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        if (from.index in local.indices && to.index in local.indices) {
            local = local.toMutableList().apply { add(to.index, removeAt(from.index)) }
            hasDragged = true
        }
    }
    LaunchedEffect(cards) { if (!reorderState.isAnyItemDragging) local = cards }
    LaunchedEffect(reorderState.isAnyItemDragging) {
        if (!reorderState.isAnyItemDragging && hasDragged) {
            actions.reorder(local.mapIndexed { index, c -> c.withPosition(index.toDouble()) })
            hasDragged = false
        }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(local, key = { it.id }) { card ->
            val dragging = reorderState.draggingKey == card.id
            val itemModifier = if (dragging) {
                Modifier.zIndex(1f).graphicsLayer { translationY = reorderState.draggingItemTranslation }
            } else {
                Modifier.animateItem()
            }
            ReorderableItem(reorderState, key = card.id, modifier = itemModifier) { isDragging ->
                val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "cardElevation")
                Surface(shadowElevation = elevation) {
                    CardRow(
                        card = card,
                        onOpen = { actions.openCard(card.id) },
                        onDelete = { actions.deleteCard(card) },
                        dragHandle = Modifier.reorderDragHandle(reorderState, key = card.id),
                    )
                }
            }
        }
    }
}

@Composable
private fun CardRow(
    card: Card,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    dragHandle: Modifier? = null,
) {
    ListItem(
        headlineContent = {
            Text(parseMarkdown(card.front.substringBefore('\n').take(60), showMarkers = false))
        },
        supportingContent = {
            Text(parseMarkdown(card.back.substringBefore('\n').take(60), showMarkers = false))
        },
        trailingContent = {
            Row {
                IconButton(onClick = onDelete) { IconDelete() }
                if (dragHandle != null) {
                    IconButton(onClick = {}, modifier = dragHandle) { IconDragHandle() }
                }
            }
        },
        modifier = Modifier.clickable { onOpen() },
    )
}
