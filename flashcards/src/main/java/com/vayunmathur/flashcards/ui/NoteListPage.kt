package com.vayunmathur.flashcards.ui

import android.content.Intent
import android.widget.Toast
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
import com.vayunmathur.flashcards.data.Note
import com.vayunmathur.flashcards.util.FlashcardsViewModel
import com.vayunmathur.flashcards.util.NoteListActions
import com.vayunmathur.flashcards.util.NoteListUiState
import com.vayunmathur.flashcards.util.NoteRow
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

/** Binds the deck with [deckId] to the stateless [NoteListScreen]. */
@Composable
fun NoteListPage(
    backStack: NavBackStack<Route>,
    viewModel: FlashcardsViewModel,
    deckId: Long,
) {
    val context = LocalContext.current
    val decks by viewModel.decks.collectAsStateWithLifecycle()
    val notes by remember(deckId) { viewModel.notesFor(deckId) }
        .collectAsStateWithLifecycle(emptyList())
    val cards by remember(deckId) { viewModel.cardsFor(deckId) }
        .collectAsStateWithLifecycle(emptyList())

    val deckName = decks.firstOrNull { it.id == deckId }?.name ?: ""
    val now = System.currentTimeMillis()
    val cardsByNote = cards.groupBy { it.noteId }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            val name = queryFileName(context, it).orEmpty()
            if (name.endsWith(".apkg", true) || isZip(context, it)) {
                viewModel.importApkg(it)
            } else {
                viewModel.importCsv(deckId, it)
            }
        }
    }

    LaunchedEffect(viewModel, deckId) {
        viewModel.shareRequests.collect { uri ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.share_deck)),
            )
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    val actions = remember(backStack, viewModel, deckId) {
        object : NoteListActions {
            override fun back() { backStack.pop() }
            override fun openNote(id: Long) { backStack.add(Route.NoteEdit(deckId, id)) }
            override fun addNote() { backStack.add(Route.NoteEdit(deckId, 0)) }
            override fun deleteNote(note: Note) { viewModel.deleteNote(note) }
            override fun study() { backStack.add(Route.Review(deckId)) }
            override fun reorder(notes: List<Note>) { viewModel.reorderNotes(notes) }
            override fun openStats() { backStack.add(Route.Stats) }
            override fun share() { viewModel.exportApkg(deckId) }
        }
    }

    NoteListScreen(
        state = NoteListUiState(
            deckName = deckName,
            notes = notes.sortedBy { it.position }
                .map { NoteRow(it, cardsByNote[it.id]?.size ?: 0) },
            dueCount = cards.count { (!it.isNew && it.dueDate <= now) || it.isNew },
        ),
        actions = actions,
        onImport = {
            importLauncher.launch(
                arrayOf(
                    "application/octet-stream",
                    "application/zip",
                    "text/csv",
                    "text/comma-separated-values",
                    "text/plain",
                    "*/*",
                ),
            )
        },
    )
}

/**
 * The note list for one deck, with no dependency on the ViewModel or the back stack so it
 * can be rendered from a `@Preview` — see `src/screenshotTest`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteListScreen(
    state: NoteListUiState,
    actions: NoteListActions,
    onImport: () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    val filtered = if (query.isBlank()) {
        state.notes
    } else {
        state.notes.filter {
            it.note.flds.contains(query, true) || it.note.tags.contains(query, true)
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
            FloatingActionButton(onClick = { actions.addNote() }) { IconAdd() }
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
            if (state.notes.isNotEmpty()) {
                CommonSearchBar(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.search_cards),
                    padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            when {
                state.notes.isEmpty() -> EmptyState(
                    title = stringResource(R.string.no_cards),
                    message = stringResource(R.string.no_cards_hint),
                    icon = { IconAdd() },
                    modifier = Modifier.fillMaxSize(),
                )
                query.isBlank() -> ReorderableNoteList(filtered, actions)
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.note.id }) { row ->
                        NoteRowItem(row, onOpen = { actions.openNote(row.note.id) }, onDelete = { actions.deleteNote(row.note) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReorderableNoteList(rows: List<NoteRow>, actions: NoteListActions) {
    val listState = rememberLazyListState()
    var local by remember { mutableStateOf(rows) }
    var hasDragged by remember { mutableStateOf(false) }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        if (from.index in local.indices && to.index in local.indices) {
            local = local.toMutableList().apply { add(to.index, removeAt(from.index)) }
            hasDragged = true
        }
    }
    LaunchedEffect(rows) { if (!reorderState.isAnyItemDragging) local = rows }
    LaunchedEffect(reorderState.isAnyItemDragging) {
        if (!reorderState.isAnyItemDragging && hasDragged) {
            actions.reorder(local.mapIndexed { index, r -> r.note.withPosition(index.toDouble()) })
            hasDragged = false
        }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(local, key = { it.note.id }) { row ->
            val dragging = reorderState.draggingKey == row.note.id
            val itemModifier = if (dragging) {
                Modifier.zIndex(1f).graphicsLayer { translationY = reorderState.draggingItemTranslation }
            } else {
                Modifier.animateItem()
            }
            ReorderableItem(reorderState, key = row.note.id, modifier = itemModifier) { isDragging ->
                val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "noteElevation")
                Surface(shadowElevation = elevation) {
                    NoteRowItem(
                        row = row,
                        onOpen = { actions.openNote(row.note.id) },
                        onDelete = { actions.deleteNote(row.note) },
                        dragHandle = Modifier.reorderDragHandle(reorderState, key = row.note.id),
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteRowItem(
    row: NoteRow,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    dragHandle: Modifier? = null,
) {
    val fields = row.note.fieldList
    ListItem(
        headlineContent = {
            Text(parseMarkdown(row.note.sortField.substringBefore('\n').take(60), showMarkers = false))
        },
        supportingContent = {
            Text(parseMarkdown(fields.getOrNull(1).orEmpty().substringBefore('\n').take(60), showMarkers = false))
        },
        trailingContent = {
            Row {
                if (row.cardCount != 1) {
                    Text(stringResource(R.string.card_count_badge, row.cardCount))
                }
                IconButton(onClick = onDelete) { IconDelete() }
                if (dragHandle != null) {
                    IconButton(onClick = {}, modifier = dragHandle) { IconDragHandle() }
                }
            }
        },
        modifier = Modifier.clickable { onOpen() },
    )
}

private fun queryFileName(context: android.content.Context, uri: android.net.Uri): String? =
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }

private fun isZip(context: android.content.Context, uri: android.net.Uri): Boolean =
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(2)
            input.read(header) == 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
        } ?: false
    }.getOrDefault(false)
