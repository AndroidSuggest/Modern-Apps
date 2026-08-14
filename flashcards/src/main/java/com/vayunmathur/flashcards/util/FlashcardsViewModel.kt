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
import com.vayunmathur.flashcards.data.Deck
import com.vayunmathur.flashcards.data.DeckDao
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

/**
 * ViewModel for the Flashcards app.
 *
 * Owns the deck/card [StateFlow]s collected by the screens, the persisted
 * [settings], and the in-memory [ReviewSession] driving the review screen via
 * [review]. Grading runs the pure [Scheduler], upserts the card, and writes a
 * [ReviewLog] row (which the stats screen reads back).
 */
class FlashcardsViewModel(
    application: Application,
    private val deckDao: DeckDao,
    private val cardDao: CardDao,
    private val reviewLogDao: ReviewLogDao,
) : AndroidViewModel(application) {

    private val ds = DataStoreUtils.getInstance(application)

    val decks: StateFlow<List<Deck>> = deckDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All cards across every deck; the deck list derives per-deck counts from this. */
    val cards: StateFlow<List<Card>> = cardDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun cardsFor(deckId: Long): Flow<List<Card>> = cardDao.getByDeckFlow(deckId)

    fun cardById(id: Long): Flow<Card?> = cardDao.getByIdFlow(id)

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

    // -- Deck / card writes ------------------------------------------------

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
        reviewLogDao.deleteByDeck(deck.id)
        deckDao.delete(deck)
    }

    fun reorderDecks(decks: List<Deck>) = launchIo { decks.forEach { deckDao.upsert(it) } }

    fun upsertCard(card: Card) = launchIo { cardDao.upsert(card) }

    fun deleteCard(card: Card) = launchIo { cardDao.delete(card) }

    fun reorderCards(cards: List<Card>) = launchIo { cardDao.upsertAll(cards) }

    // -- Review session ----------------------------------------------------

    private var session: ReviewSession? = null
    private var lastLogId: Long? = null
    private val _review = MutableStateFlow(ReviewUiState())
    val review: StateFlow<ReviewUiState> = _review.asStateFlow()

    fun startSession(deckId: Long) = launchIo {
        val deck = deckDao.getById(deckId) ?: return@launchIo
        val cards = cardDao.getByDeck(deckId)
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
            ReviewUiState(
                front = current?.front.orEmpty(),
                back = current?.back.orEmpty(),
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

    // -- Import / export / share ------------------------------------------

    private val _shareRequests = MutableSharedFlow<Uri>(extraBufferCapacity = 1)
    val shareRequests = _shareRequests.asSharedFlow()

    fun exportDeck(deckId: Long) = launchIo {
        val context = getApplication<Application>()
        val deck = deckDao.getById(deckId) ?: return@launchIo
        val cards = cardDao.getByDeck(deckId)
        val uri = withContext(Dispatchers.IO) {
            val file = DeckIo.writeExport(context, deck, cards)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        _shareRequests.emit(uri)
    }

    fun importCsv(deckId: Long, uri: Uri) = launchIo {
        val context = getApplication<Application>()
        val text = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } ?: return@launchIo
        val existing = cardDao.getByDeck(deckId)
        var position = (existing.maxOfOrNull { it.position } ?: 0.0)
        val newCards = DeckIo.parseCsv(text).map { (front, back) ->
            position += 1.0
            Card(deckId = deckId, front = front, back = back, position = position)
        }
        if (newCards.isNotEmpty()) cardDao.upsertAll(newCards)
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
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(FlashcardsViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return FlashcardsViewModel(application, deckDao, cardDao, reviewLogDao) as T
    }
}
