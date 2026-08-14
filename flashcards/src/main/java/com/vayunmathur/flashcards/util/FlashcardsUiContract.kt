package com.vayunmathur.flashcards.util

import com.vayunmathur.flashcards.data.CardTemplate
import com.vayunmathur.flashcards.data.Deck
import com.vayunmathur.flashcards.data.Note
import com.vayunmathur.flashcards.data.NoteType
import com.vayunmathur.flashcards.data.NoteTypeField

/**
 * The UI contract between [FlashcardsViewModel] plus the nav back stack and the screens.
 *
 * Screens take a state value and an actions interface rather than the ViewModel itself, so
 * they can be rendered by a `@Preview` — which is what the store listing images are
 * generated from. It lives in `util` rather than `ui` so the dependency runs one way:
 * `ui` depends on `util`, and the binders in `ui` implement these interfaces. Every actions
 * method has a no-op default so a preview can use the `Noop` implementation with no VM.
 */

// ---------------------------------------------------------------------------
// Deck list
// ---------------------------------------------------------------------------

/** A deck plus its derived review counts, as drawn on the deck list. */
data class DeckSummary(
    val deck: Deck,
    val dueCount: Int = 0,
    val newCount: Int = 0,
    val totalCount: Int = 0,
    /** Fraction of cards that have graduated to the review state (0..1). */
    val mastery: Float = 0f,
)

/** Everything the deck list draws. */
data class DeckListUiState(
    val decks: List<DeckSummary> = emptyList(),
)

interface DeckListActions {
    fun openDeck(id: Long) {}
    fun addDeck(name: String) {}
    fun deleteDeck(deck: Deck) {}
    fun startReview(deckId: Long) {}
    fun reorder(decks: List<Deck>) {}

    companion object {
        val Noop: DeckListActions = object : DeckListActions {}
    }
}

// ---------------------------------------------------------------------------
// Note list
// ---------------------------------------------------------------------------

/** A note plus the number of cards it currently generates, drawn on the note list. */
data class NoteRow(
    val note: Note,
    val cardCount: Int = 1,
)

/** Everything the note list draws for a single deck. */
data class NoteListUiState(
    val deckName: String = "",
    val notes: List<NoteRow> = emptyList(),
    val dueCount: Int = 0,
)

interface NoteListActions {
    fun back() {}
    fun openNote(id: Long) {}
    fun addNote() {}
    fun deleteNote(note: Note) {}
    fun study() {}
    fun reorder(notes: List<Note>) {}
    fun openStats() {}
    fun share() {}

    companion object {
        val Noop: NoteListActions = object : NoteListActions {}
    }
}

// ---------------------------------------------------------------------------
// Note editor
// ---------------------------------------------------------------------------

/** A note type and its ordered field names, used to drive the note editor's fields. */
data class NoteTypeConfig(
    val id: Long,
    val name: String,
    val fieldNames: List<String>,
)

/** Everything the note editor draws: the selected note type, its fields, and tags. */
data class NoteEditUiState(
    val initialNoteTypeId: Long = 0,
    val initialDeckId: Long = 0,
    /** Field values in the selected note type's field order. */
    val initialFieldValues: List<String> = emptyList(),
    val initialTags: String = "",
    val isNew: Boolean = true,
    val noteTypes: List<NoteTypeConfig> = emptyList(),
    val decks: List<DeckOption> = emptyList(),
)

interface NoteEditActions {
    fun back() {}
    fun save(noteTypeId: Long, deckId: Long, fieldValues: List<String>, tags: String) {}
    fun deleteNote() {}

    companion object {
        val Noop: NoteEditActions = object : NoteEditActions {}
    }
}

// ---------------------------------------------------------------------------
// Note type list + editor
// ---------------------------------------------------------------------------

/** A note type plus its counts, drawn on the note-type management list. */
data class NoteTypeSummary(
    val id: Long,
    val name: String,
    val fieldCount: Int,
    val templateCount: Int,
    val noteCount: Int,
    val isCloze: Boolean,
)

data class NoteTypeListUiState(
    val noteTypes: List<NoteTypeSummary> = emptyList(),
)

interface NoteTypeListActions {
    fun back() {}
    fun openNoteType(id: Long) {}
    fun addNoteType() {}
    fun deleteNoteType(id: Long) {}

    companion object {
        val Noop: NoteTypeListActions = object : NoteTypeListActions {}
    }
}

/** An editable template draft in the note-type editor. */
data class TemplateDraft(
    val name: String,
    val qfmt: String,
    val afmt: String,
)

/** Everything the note-type editor draws for one note type. */
data class NoteTypeEditUiState(
    val id: Long = 0,
    val name: String = "",
    val css: String = "",
    /** [com.vayunmathur.flashcards.data.NoteTypeKind]. Cloze note types have a fixed single template. */
    val type: Int = 0,
    val fields: List<String> = emptyList(),
    val templates: List<TemplateDraft> = emptyList(),
    val isNew: Boolean = true,
)

interface NoteTypeEditActions {
    fun back() {}
    fun save(name: String, css: String, type: Int, fields: List<String>, templates: List<TemplateDraft>) {}
    fun delete() {}

    companion object {
        val Noop: NoteTypeEditActions = object : NoteTypeEditActions {}
    }
}

/** Bundles a note type with its ordered fields and templates for in-memory caching. */
data class NoteTypeWithConfig(
    val noteType: NoteType,
    val fields: List<NoteTypeField>,
    val templates: List<CardTemplate>,
)

// ---------------------------------------------------------------------------
// Review session
// ---------------------------------------------------------------------------

/** Everything a review session draws for the current card. */
data class ReviewUiState(
    val front: String = "",
    val back: String = "",
    /** Cards still queued in this session, including the one shown. */
    val remaining: Int = 0,
    /** True when the queue is empty, i.e. the session is complete. */
    val done: Boolean = false,
    val newCount: Int = 0,
    val learningCount: Int = 0,
    val reviewCount: Int = 0,
    val progress: Float = 0f,
    /** Predicted next-interval label per grade button (e.g. "10m", "4d"). */
    val intervalLabels: Map<Grade, String> = emptyMap(),
    val canUndo: Boolean = false,
) {
    fun label(grade: Grade): String = intervalLabels[grade] ?: ""
}

interface ReviewActions {
    fun back() {}
    fun grade(grade: Grade) {}
    fun undo() {}

    companion object {
        val Noop: ReviewActions = object : ReviewActions {}
    }
}

// ---------------------------------------------------------------------------
// Statistics
// ---------------------------------------------------------------------------

/** A single deck choice (plus an "All decks" option with a null id) in the stats picker. */
data class DeckOption(val id: Long?, val name: String)

/** One day of review history. [epochDay] is days since the Unix epoch (local). */
data class DailyStat(val epochDay: Long, val count: Int)

data class StatsUiState(
    val deckOptions: List<DeckOption> = emptyList(),
    val selectedDeckId: Long? = null,
    /** Chronological daily review counts covering roughly the last year. */
    val daily: List<DailyStat> = emptyList(),
    val totalReviews: Int = 0,
    val retentionPct: Int = 0,
    val streakDays: Int = 0,
    val matureCards: Int = 0,
    val totalCards: Int = 0,
)

interface StatsActions {
    fun back() {}
    fun selectDeck(id: Long?) {}

    companion object {
        val Noop: StatsActions = object : StatsActions {}
    }
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

object ThemeMode {
    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2
}

data class SettingsUiState(
    val desiredRetention: Double = 0.9,
    val newPerDay: Int = 20,
    val maxReviews: Int = 200,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
    val themeMode: Int = ThemeMode.SYSTEM,
)

interface SettingsActions {
    fun back() {}
    fun setDesiredRetention(value: Double) {}
    fun setNewPerDay(value: Int) {}
    fun setMaxReviews(value: Int) {}
    fun setReminderEnabled(enabled: Boolean) {}
    fun setReminderTime(hour: Int, minute: Int) {}
    fun setThemeMode(mode: Int) {}
    fun manageNoteTypes() {}
    fun exportCollection() {}

    companion object {
        val Noop: SettingsActions = object : SettingsActions {}
    }
}
