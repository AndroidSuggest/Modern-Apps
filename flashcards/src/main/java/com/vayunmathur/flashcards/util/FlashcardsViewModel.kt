package com.vayunmathur.flashcards.util

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.flashcards.data.Card
import com.vayunmathur.flashcards.data.CardDao
import com.vayunmathur.flashcards.data.CardState
import com.vayunmathur.flashcards.data.CardTemplate
import com.vayunmathur.flashcards.data.CardTemplateDao
import com.vayunmathur.flashcards.data.Deck
import com.vayunmathur.flashcards.data.DeckDao
import com.vayunmathur.flashcards.data.FIELD_SEPARATOR
import com.vayunmathur.flashcards.data.Note
import com.vayunmathur.flashcards.data.NoteDao
import com.vayunmathur.flashcards.data.NoteType
import com.vayunmathur.flashcards.data.NoteTypeDao
import com.vayunmathur.flashcards.data.NoteTypeField
import com.vayunmathur.flashcards.data.NoteTypeFieldDao
import com.vayunmathur.flashcards.data.NoteTypeKind
import com.vayunmathur.flashcards.data.ReviewLog
import com.vayunmathur.flashcards.data.ReviewLogDao
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * ViewModel for the Flashcards app.
 *
 * Owns the deck/note/card [StateFlow]s collected by the screens, the cached
 * [noteTypes] (Anki-style models), the persisted [settings], and the in-memory
 * [ReviewSession] driving the review screen via [review]. Card content is rendered
 * on demand from a note + its template through [TemplateEngine]. Grading runs the
 * pure [Scheduler], upserts the card, and writes a [ReviewLog] row.
 */
class FlashcardsViewModel(
    application: Application,
    private val deckDao: DeckDao,
    private val cardDao: CardDao,
    private val reviewLogDao: ReviewLogDao,
    private val noteTypeDao: NoteTypeDao,
    private val noteTypeFieldDao: NoteTypeFieldDao,
    private val cardTemplateDao: CardTemplateDao,
    private val noteDao: NoteDao,
) : AndroidViewModel(application) {

    private val ds = DataStoreUtils.getInstance(application)

    val decks: StateFlow<List<Deck>> = deckDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All cards across every deck; the deck list derives per-deck counts from this. */
    val cards: StateFlow<List<Card>> = cardDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All notes across every deck; the note-type manager derives per-type counts from this. */
    val notes: StateFlow<List<Note>> = noteDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every note type with its ordered fields and templates. */
    val noteTypes: StateFlow<List<NoteTypeWithConfig>> = combine(
        noteTypeDao.getAllFlow(),
        noteTypeFieldDao.getAllFlow(),
        cardTemplateDao.getAllFlow(),
    ) { types, fields, templates ->
        types.map { type ->
            NoteTypeWithConfig(
                noteType = type,
                fields = fields.filter { it.noteTypeId == type.id }.sortedBy { it.ord },
                templates = templates.filter { it.noteTypeId == type.id }.sortedBy { it.ord },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        launchIo { ensureBuiltInNoteTypes() }
    }

    fun notesFor(deckId: Long): Flow<List<Note>> = noteDao.getByDeckFlow(deckId)

    fun noteById(id: Long): Flow<Note?> = noteDao.getByIdFlow(id)

    fun cardsFor(deckId: Long): Flow<List<Card>> = cardDao.getByDeckFlow(deckId)

    fun reviewLogsFor(deckId: Long?): Flow<List<ReviewLog>> =
        if (deckId == null) reviewLogDao.getAllFlow() else reviewLogDao.getByDeckFlow(deckId)

    // -- Persisted settings ------------------------------------------------

    val settings: StateFlow<SettingsUiState> = combine(
        ds.doubleFlow(KEY_RETENTION),
        ds.longFlow(KEY_NEW_PER_DAY, 20L),
        ds.longFlow(KEY_MAX_REVIEWS, 200L),
        ds.longFlow(KEY_THEME_MODE, 0L),
    ) { retention, newPerDay, maxReviews, theme ->
        arrayOf<Any>(retention, newPerDay, maxReviews, theme)
    }
        .combine(ds.booleanFlow(KEY_REMINDER_ENABLED)) { core, enabled -> core to enabled }
        .combine(ds.longFlow(KEY_REMINDER_MINUTES, 20L * 60)) { (core, enabled), minutes ->
            SettingsUiState(
                desiredRetention = (core[0] as Double).takeIf { it > 0.0 } ?: 0.9,
                newPerDay = (core[1] as Long).toInt(),
                maxReviews = (core[2] as Long).toInt(),
                themeMode = (core[3] as Long).toInt(),
                reminderEnabled = enabled,
                reminderHour = (minutes / 60).toInt(),
                reminderMinute = (minutes % 60).toInt(),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setDesiredRetention(value: Double) =
        launchIo { ds.setDouble(KEY_RETENTION, value) }

    fun setNewPerDay(value: Int) = launchIo { ds.setLong(KEY_NEW_PER_DAY, value.toLong()) }

    fun setMaxReviews(value: Int) = launchIo { ds.setLong(KEY_MAX_REVIEWS, value.toLong()) }

    fun setThemeMode(mode: Int) = launchIo { ds.setLong(KEY_THEME_MODE, mode.toLong()) }

    fun setReminderEnabled(enabled: Boolean) = launchIo {
        ds.setBoolean(KEY_REMINDER_ENABLED, enabled)
        val current = settings.value
        ReviewReminder.update(getApplication(), enabled, current.reminderHour, current.reminderMinute)
    }

    fun setReminderTime(hour: Int, minute: Int) = launchIo {
        ds.setLong(KEY_REMINDER_MINUTES, (hour * 60 + minute).toLong())
        ReviewReminder.update(getApplication(), settings.value.reminderEnabled, hour, minute)
    }

    // -- Deck writes -------------------------------------------------------

    fun upsertDeck(deck: Deck) = launchIo { deckDao.upsert(deck) }

    fun addDeck(name: String) = launchIo {
        val s = settings.value
        deckDao.upsert(
            Deck(
                name = name,
                newPerDay = s.newPerDay,
                maxReviewsPerDay = s.maxReviews,
                desiredRetention = s.desiredRetention,
            ),
        )
    }

    fun deleteDeck(deck: Deck) = launchIo {
        cardDao.deleteByDeck(deck.id)
        noteDao.deleteByDeck(deck.id)
        reviewLogDao.deleteByDeck(deck.id)
        deckDao.delete(deck)
    }

    fun reorderDecks(decks: List<Deck>) = launchIo { decks.forEach { deckDao.upsert(it) } }

    // -- Note writes -------------------------------------------------------

    fun saveNote(
        noteId: Long,
        noteTypeId: Long,
        deckId: Long,
        fieldValues: List<String>,
        tags: String,
    ) = launchIo {
        val cfg = noteTypes.value.firstOrNull { it.noteType.id == noteTypeId } ?: return@launchIo
        val flds = fieldValues.joinToString(FIELD_SEPARATOR)
        val sortField = fieldValues.firstOrNull().orEmpty()
        val existing = if (noteId != 0L) noteDao.getById(noteId) else null
        val position = existing?.position
            ?: ((noteDao.getByDeck(deckId).maxOfOrNull { it.position } ?: 0.0) + 1.0)
        val note = Note(
            id = noteId,
            noteTypeId = noteTypeId,
            deckId = deckId,
            guid = existing?.guid ?: randomGuid(),
            flds = flds,
            sortField = sortField,
            tags = tags.trim(),
            mod = nowSeconds(),
            position = position,
        )
        val savedId = noteDao.upsert(note)
        val finalNote = if (noteId == 0L) note.copy(id = savedId) else note
        CardGenerator.regenerate(finalNote, cfg.noteType, cfg.templates, cfg.fields, cardDao)
        // Keep generated cards in the note's deck (handles a note being moved decks).
        val misplaced = cardDao.getByNote(finalNote.id).filter { it.deckId != finalNote.deckId }
        if (misplaced.isNotEmpty()) {
            cardDao.upsertAll(misplaced.map { it.copy(deckId = finalNote.deckId) })
        }
    }

    fun deleteNote(note: Note) = launchIo {
        cardDao.deleteByNote(note.id)
        noteDao.delete(note)
    }

    fun reorderNotes(notes: List<Note>) = launchIo { noteDao.upsertAll(notes) }

    // -- Note type CRUD ----------------------------------------------------

    fun saveNoteType(
        id: Long,
        name: String,
        css: String,
        type: Int,
        fieldNames: List<String>,
        templates: List<TemplateDraft>,
    ) = launchIo {
        val savedId = noteTypeDao.upsert(NoteType(id = id, name = name, type = type, css = css, mod = nowSeconds()))
        val ntId = if (id == 0L) savedId else id

        noteTypeFieldDao.deleteByNoteType(ntId)
        noteTypeFieldDao.upsertAll(
            fieldNames.mapIndexed { ord, fieldName -> NoteTypeField(noteTypeId = ntId, ord = ord, name = fieldName) },
        )

        cardTemplateDao.deleteByNoteType(ntId)
        val effective = if (type == NoteTypeKind.CLOZE) templates.take(1) else templates
        cardTemplateDao.upsertAll(
            effective.mapIndexed { ord, t ->
                CardTemplate(noteTypeId = ntId, ord = ord, name = t.name, qfmt = t.qfmt, afmt = t.afmt)
            },
        )

        // Regenerate cards for every note of this type against the new templates.
        val nt = noteTypeDao.getById(ntId) ?: return@launchIo
        val newFields = noteTypeFieldDao.getByNoteType(ntId)
        val newTemplates = cardTemplateDao.getByNoteType(ntId)
        noteDao.getByNoteType(ntId).forEach { note ->
            CardGenerator.regenerate(note, nt, newTemplates, newFields, cardDao)
        }
    }

    fun deleteNoteType(id: Long) = launchIo {
        if (id in BUILT_IN_NOTE_TYPE_IDS) return@launchIo
        val notes = noteDao.getByNoteType(id)
        notes.forEach { cardDao.deleteByNote(it.id) }
        notes.forEach { noteDao.delete(it) }
        noteTypeFieldDao.deleteByNoteType(id)
        cardTemplateDao.deleteByNoteType(id)
        noteTypeDao.getById(id)?.let { noteTypeDao.delete(it) }
    }

    // -- Review session ----------------------------------------------------

    private var session: ReviewSession? = null
    private var lastLogId: Long? = null
    private var sessionNotes: Map<Long, Note> = emptyMap()
    private val _review = MutableStateFlow(ReviewUiState())
    val review: StateFlow<ReviewUiState> = _review.asStateFlow()

    fun startSession(deckId: Long) = launchIo {
        val deck = deckDao.getById(deckId) ?: return@launchIo
        val cards = cardDao.getByDeck(deckId)
        sessionNotes = noteDao.getByDeck(deckId).associateBy { it.id }
        val now = System.currentTimeMillis()
        session = ReviewSession(
            cards = cards,
            newPerDay = deck.newPerDay,
            maxReviews = deck.maxReviewsPerDay,
            now = now,
            desiredRetention = deck.desiredRetention,
        )
        lastLogId = null
        publishReview()
    }

    fun gradeCurrent(grade: Grade) = launchIo {
        val s = session ?: return@launchIo
        val original = s.current ?: return@launchIo
        val now = System.currentTimeMillis()
        val prevLastReview = original.lastReview
        val updated = s.grade(grade, now) ?: return@launchIo
        cardDao.upsert(updated)
        val elapsedDays =
            if (prevLastReview > 0) (now - prevLastReview).toDouble() / Scheduler.DAY_MS else 0.0
        val scheduledDays = (updated.dueDate - now).toDouble() / Scheduler.DAY_MS
        lastLogId = reviewLogDao.insert(
            ReviewLog(
                cardId = original.id,
                deckId = original.deckId,
                reviewedAt = now,
                grade = grade.value,
                elapsedDays = elapsedDays,
                scheduledDays = scheduledDays,
                state = updated.state,
            ),
        )
        publishReview()
    }

    fun undoReview() = launchIo {
        val s = session ?: return@launchIo
        val restored = s.undo() ?: return@launchIo
        cardDao.upsert(restored)
        lastLogId?.let {
            reviewLogDao.deleteById(it)
            lastLogId = null
        }
        publishReview()
    }

    private fun publishReview() {
        val s = session
        _review.value = if (s == null) {
            ReviewUiState(done = true)
        } else {
            val now = System.currentTimeMillis()
            val current = s.current
            val (front, back) = current?.let { renderCard(it) } ?: ("" to "")
            ReviewUiState(
                front = front,
                back = back,
                remaining = s.remaining,
                done = s.done,
                newCount = s.newCount,
                learningCount = s.learningCount,
                reviewCount = s.reviewCount,
                progress = s.progress,
                intervalLabels = s.previewLabels(now),
                canUndo = s.canUndo,
            )
        }
    }

    /** Renders a card's (front, back) markdown from its note and template. */
    private fun renderCard(card: Card): Pair<String, String> {
        val note = sessionNotes[card.noteId] ?: return "" to ""
        val cfg = noteTypes.value.firstOrNull { it.noteType.id == note.noteTypeId }
            ?: return note.sortField to ""
        val values = note.fieldValues(cfg.fields)
        return if (cfg.noteType.type == NoteTypeKind.CLOZE) {
            val template = cfg.templates.firstOrNull() ?: return note.sortField to ""
            TemplateEngine.render(template.qfmt, template.afmt, values, clozeOrd = card.templateOrd)
        } else {
            val template = cfg.templates.firstOrNull { it.ord == card.templateOrd }
                ?: cfg.templates.firstOrNull()
                ?: return note.sortField to ""
            TemplateEngine.render(template.qfmt, template.afmt, values, clozeOrd = null)
        }
    }

    // -- Import / export / share ------------------------------------------

    private val _shareRequests = MutableSharedFlow<Uri>(extraBufferCapacity = 1)
    val shareRequests = _shareRequests.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    /** Exports [deckId] (or the whole collection when null) as a shareable `.apkg`. */
    fun exportApkg(deckId: Long?) = launchIo {
        val context = getApplication<Application>()
        val exportedDecks =
            if (deckId == null) deckDao.getAll() else listOfNotNull(deckDao.getById(deckId))
        val notes = if (deckId == null) noteDao.getAll() else noteDao.getByDeck(deckId)
        val noteIds = notes.map { it.id }.toSet()
        val exportedCards = cardDao.getAll().filter { it.noteId in noteIds }
        val configs = noteTypes.value
        val uri = withContext(Dispatchers.IO) {
            val name = exportedDecks.singleOrNull()?.name ?: "collection"
            val file = ApkgExport.write(context, name, exportedDecks, notes, exportedCards, configs)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        _shareRequests.emit(uri)
    }

    fun importApkg(uri: Uri) = launchIo {
        val context = getApplication<Application>()
        val message = withContext(Dispatchers.IO) {
            runCatching {
                ApkgImport.import(
                    context, uri, deckDao, noteTypeDao, noteTypeFieldDao, cardTemplateDao, noteDao, cardDao,
                )
            }.getOrElse { it.message ?: "Import failed" }
        }
        _messages.emit(message)
    }

    fun importCsv(deckId: Long, uri: Uri) = launchIo {
        val context = getApplication<Application>()
        val cfg = noteTypes.value.firstOrNull { it.noteType.id == BASIC_NOTE_TYPE_ID } ?: return@launchIo
        val text = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } ?: return@launchIo
        val rows = DeckIo.parseCsv(text)
        if (rows.isEmpty()) return@launchIo
        var position = noteDao.getByDeck(deckId).maxOfOrNull { it.position } ?: 0.0
        rows.forEach { (front, back) ->
            position += 1.0
            val note = Note(
                noteTypeId = BASIC_NOTE_TYPE_ID,
                deckId = deckId,
                guid = randomGuid(),
                flds = listOf(front, back).joinToString(FIELD_SEPARATOR),
                sortField = front,
                mod = nowSeconds(),
                position = position,
            )
            val id = noteDao.upsert(note)
            CardGenerator.regenerate(note.copy(id = id), cfg.noteType, cfg.templates, cfg.fields, cardDao)
        }
    }

    // -- Built-in note types ----------------------------------------------

    private suspend fun ensureBuiltInNoteTypes() {
        if (noteTypeDao.getAll().isNotEmpty()) return
        seedNoteType(
            id = BASIC_NOTE_TYPE_ID,
            name = "Basic",
            type = NoteTypeKind.STANDARD,
            fields = listOf("Front", "Back"),
            templates = listOf(TemplateDraft("Card 1", "{{Front}}", "{{FrontSide}}\n\n---\n\n{{Back}}")),
        )
        seedNoteType(
            id = 2,
            name = "Basic (and reversed card)",
            type = NoteTypeKind.STANDARD,
            fields = listOf("Front", "Back"),
            templates = listOf(
                TemplateDraft("Card 1", "{{Front}}", "{{FrontSide}}\n\n---\n\n{{Back}}"),
                TemplateDraft("Card 2", "{{Back}}", "{{FrontSide}}\n\n---\n\n{{Front}}"),
            ),
        )
        seedNoteType(
            id = 3,
            name = "Cloze",
            type = NoteTypeKind.CLOZE,
            fields = listOf("Text", "Back Extra"),
            templates = listOf(
                TemplateDraft("Cloze", "{{cloze:Text}}", "{{cloze:Text}}\n\n---\n\n{{Back Extra}}"),
            ),
        )
    }

    private suspend fun seedNoteType(
        id: Long,
        name: String,
        type: Int,
        fields: List<String>,
        templates: List<TemplateDraft>,
    ) {
        noteTypeDao.upsert(NoteType(id = id, name = name, type = type, mod = nowSeconds()))
        noteTypeFieldDao.upsertAll(fields.mapIndexed { ord, f -> NoteTypeField(noteTypeId = id, ord = ord, name = f) })
        cardTemplateDao.upsertAll(
            templates.mapIndexed { ord, t -> CardTemplate(noteTypeId = id, ord = ord, name = t.name, qfmt = t.qfmt, afmt = t.afmt) },
        )
    }

    private fun launchIo(block: suspend () -> Unit) =
        viewModelScope.launch(Dispatchers.IO) { block() }

    companion object {
        const val KEY_RETENTION = "desired_retention"
        const val KEY_NEW_PER_DAY = "new_per_day"
        const val KEY_MAX_REVIEWS = "max_reviews"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_REMINDER_ENABLED = "reminder_enabled"
        const val KEY_REMINDER_MINUTES = "reminder_minutes"

        const val BASIC_NOTE_TYPE_ID = 1L
        val BUILT_IN_NOTE_TYPE_IDS = setOf(1L, 2L, 3L)

        private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

        /** A 16-hex-char random note guid, matching the migration's format. */
        fun randomGuid(): String = (0 until 8).joinToString("") {
            "%02x".format(Random.nextInt(0, 256))
        }

        /** Mastery = fraction of a deck's cards that have graduated to review. */
        fun mastery(cards: List<Card>): Float {
            if (cards.isEmpty()) return 0f
            return cards.count { it.state == CardState.REVIEW }.toFloat() / cards.size
        }
    }
}

/** Factory for constructing [FlashcardsViewModel] with the DAOs. */
class FlashcardsViewModelFactory(
    private val application: Application,
    private val deckDao: DeckDao,
    private val cardDao: CardDao,
    private val reviewLogDao: ReviewLogDao,
    private val noteTypeDao: NoteTypeDao,
    private val noteTypeFieldDao: NoteTypeFieldDao,
    private val cardTemplateDao: CardTemplateDao,
    private val noteDao: NoteDao,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(FlashcardsViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return FlashcardsViewModel(
            application, deckDao, cardDao, reviewLogDao,
            noteTypeDao, noteTypeFieldDao, cardTemplateDao, noteDao,
        ) as T
    }
}
