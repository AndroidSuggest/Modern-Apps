package com.vayunmathur.flashcards.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
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
import com.vayunmathur.flashcards.util.DeckOption
import com.vayunmathur.flashcards.util.FlashcardsViewModel
import com.vayunmathur.flashcards.util.NoteEditActions
import com.vayunmathur.flashcards.util.NoteEditUiState
import com.vayunmathur.flashcards.util.NoteTypeConfig
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.MarkdownEditor
import com.vayunmathur.library.ui.MarkdownFormatToolbar
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack

/**
 * Binds the note with [noteId] (0 for a new note) in [deckId] to the stateless
 * [NoteEditScreen]: resolves the available note types + decks and saves via the ViewModel.
 */
@Composable
fun NoteEditPage(
    backStack: NavBackStack<Route>,
    viewModel: FlashcardsViewModel,
    deckId: Long,
    noteId: Long,
) {
    val dbNote by remember(noteId) { viewModel.noteById(noteId) }
        .collectAsStateWithLifecycle(null)
    val noteTypes by viewModel.noteTypes.collectAsStateWithLifecycle()
    val decks by viewModel.decks.collectAsStateWithLifecycle()

    val configs = noteTypes.map { NoteTypeConfig(it.noteType.id, it.noteType.name, it.fields.map { f -> f.name }) }
    val isNew = noteId == 0L

    val actions = remember(backStack, viewModel, deckId, noteId) {
        object : NoteEditActions {
            override fun back() { backStack.pop() }
            override fun save(noteTypeId: Long, deckId: Long, fieldValues: List<String>, tags: String) {
                viewModel.saveNote(noteId, noteTypeId, deckId, fieldValues, tags)
                backStack.pop()
            }
            override fun deleteNote() {
                dbNote?.let { viewModel.deleteNote(it) }
                backStack.pop()
            }
        }
    }

    NoteEditScreen(
        state = NoteEditUiState(
            initialNoteTypeId = dbNote?.noteTypeId ?: configs.firstOrNull()?.id ?: FlashcardsViewModel.BASIC_NOTE_TYPE_ID,
            initialDeckId = dbNote?.deckId ?: deckId,
            initialFieldValues = dbNote?.fieldList ?: emptyList(),
            initialTags = dbNote?.tags.orEmpty(),
            isNew = isNew,
            noteTypes = configs,
            decks = decks.map { DeckOption(it.id, it.name) },
        ),
        actions = actions,
    )
}

/**
 * The note editor, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview`. Draws a note-type dropdown, a deck picker, one
 * [MarkdownEditor] per field of the selected type, and a tags field.
 */
@Composable
fun NoteEditScreen(
    state: NoteEditUiState,
    actions: NoteEditActions,
) {
    var typeId by remember { mutableLongStateOf(state.initialNoteTypeId) }
    var deckId by remember { mutableLongStateOf(state.initialDeckId) }
    var tags by remember { mutableStateOf(state.initialTags) }
    var activeField by remember { mutableIntStateOf(-1) }
    // Field values keyed by field name so they survive a note-type switch.
    val values = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    val fieldTfv = remember { mutableStateListOf<TextFieldValue>() }

    fun currentFields(): List<String> = state.noteTypes.firstOrNull { it.id == typeId }?.fieldNames ?: emptyList()

    fun rebuildEditors() {
        val fields = currentFields()
        fieldTfv.clear()
        fields.forEach { name -> fieldTfv.add(TextFieldValue(values[name].orEmpty())) }
    }

    LaunchedEffect(state) {
        typeId = state.initialNoteTypeId
        deckId = state.initialDeckId
        tags = state.initialTags
        values.clear()
        val fields = state.noteTypes.firstOrNull { it.id == typeId }?.fieldNames ?: emptyList()
        fields.forEachIndexed { i, name -> values[name] = state.initialFieldValues.getOrNull(i) ?: "" }
        rebuildEditors()
    }

    AppScaffold(
        title = if (state.isNew) stringResource(R.string.new_card) else stringResource(R.string.edit_card),
        onNavigateBack = { actions.back() },
        actions = {
            if (!state.isNew) {
                IconButton(onClick = { actions.deleteNote() }) { IconDelete() }
            }
            IconButton(onClick = {
                val fields = currentFields()
                actions.save(typeId, deckId, fields.map { values[it].orEmpty() }, tags)
            }) { IconSave() }
        },
        bottomBar = {
            val index = activeField
            if (index in fieldTfv.indices) {
                MarkdownFormatToolbar(
                    value = fieldTfv[index],
                    onValueChange = {
                        fieldTfv[index] = it
                        values[currentFields()[index]] = it.text
                    },
                )
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
            DropdownField(
                label = stringResource(R.string.note_type),
                selected = state.noteTypes.firstOrNull { it.id == typeId }?.name.orEmpty(),
                options = state.noteTypes,
                optionLabel = { it.name },
                onSelect = { config ->
                    typeId = config.id
                    config.fieldNames.forEach { name -> values.getOrPut(name) { "" } }
                    rebuildEditors()
                    activeField = -1
                },
            )
            DropdownField(
                label = stringResource(R.string.deck),
                selected = state.decks.firstOrNull { it.id == deckId }?.name.orEmpty(),
                options = state.decks,
                optionLabel = { it.name },
                onSelect = { deck -> deck.id?.let { deckId = it } },
            )

            currentFields().forEachIndexed { index, name ->
                if (index in fieldTfv.indices) {
                    Text(name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                    MarkdownEditor(
                        value = fieldTfv[index],
                        onValueChange = {
                            fieldTfv[index] = it
                            values[name] = it.text
                        },
                        placeholder = name,
                        onFocusChanged = { if (it) activeField = index },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }

            OutlinedTextField(
                value = tags,
                onValueChange = { tags = it },
                label = { Text(stringResource(R.string.tags)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun <T> DropdownField(
    label: String,
    selected: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.fillMaxWidth().clickable { expanded = true }.padding(vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(selected.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
