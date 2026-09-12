package com.vayunmathur.games.solitaire.platform

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.AndroidViewModel
import com.vayunmathur.games.solitaire.data.*
import com.vayunmathur.library.util.AchievementsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DragInfo(
    val cards: List<Card>,
    val sourceId: String,
    val offset: Offset = Offset.Zero,
    val startPos: Offset = Offset.Zero,
    val cardSize: androidx.compose.ui.unit.IntSize = androidx.compose.ui.unit.IntSize.Zero
)

class SolitaireViewModel(application: Application) : AndroidViewModel(application), SolitaireActions {
    internal val _uiState = MutableStateFlow(SolitaireUiState())
    val uiState: StateFlow<SolitaireUiState> = _uiState.asStateFlow()

    internal val _dragInfo = MutableStateFlow<DragInfo?>(null)
    override val dragInfo: StateFlow<DragInfo?> = _dragInfo.asStateFlow()

    internal val statsRepository = SolitaireStatsRepository(application)
    internal val settingsRepository = SolitaireSettingsRepository(application)
    override val dropTargets: MutableMap<String, Rect> = mutableMapOf()

    private val _cardColorScheme = MutableStateFlow(settingsRepository.getCardColorScheme())
    val cardColorScheme: StateFlow<CardColorScheme> = _cardColorScheme.asStateFlow()

    fun setCardColorScheme(scheme: CardColorScheme) {
        settingsRepository.setCardColorScheme(scheme)
        _cardColorScheme.value = scheme
    }

    val achievementsManager: AchievementsManager = run {
        val json = application.assets.open("achievements.json")
            .bufferedReader().readText()
        SolitaireAchievementsManager(application, json, statsRepository)
    }

    init {
        achievementsManager.checkExistingAchievements()
    }

    fun getStats(mode: GameMode): GameStats = statsRepository.getModeStats(mode)

    fun hasActiveGame(): Boolean = with(_uiState.value) {
        when (gameMode) {
            GameMode.KLONDIKE -> klondike?.isWon == false
            GameMode.SPIDER -> spider?.isWon == false
            GameMode.FREECELL -> freeCell?.isWon == false
            GameMode.PYRAMID -> pyramid?.isWon == false
            null -> false
        }
    }

    fun selectMode(mode: GameMode, config: GameConfig = GameConfig()) {
        when (mode) {
            GameMode.KLONDIKE -> newKlondikeGame(config)
            GameMode.SPIDER -> newSpiderGame(config)
            GameMode.FREECELL -> newFreeCellGame()
            GameMode.PYRAMID -> newPyramidGame(config)
        }
    }

    /** The config of the currently active game, for restart / play-again. */
    fun currentConfig(): GameConfig = with(_uiState.value) {
        when (gameMode) {
            GameMode.KLONDIKE -> klondike?.let { GameConfig(drawMode = it.drawMode, klondikeDifficulty = it.difficulty) }
            GameMode.SPIDER -> spider?.let { GameConfig(spiderSuits = it.suitCount) }
            GameMode.PYRAMID -> pyramid?.let { GameConfig(relaxed = it.relaxed) }
            else -> null
        } ?: GameConfig()
    }

    internal fun currentVariant(): String = with(_uiState.value) {
        when (gameMode) {
            GameMode.KLONDIKE -> klondike?.variant
            GameMode.SPIDER -> spider?.variant
            GameMode.FREECELL -> freeCell?.variant
            GameMode.PYRAMID -> pyramid?.variant
            null -> null
        } ?: ""
    }

    override fun giveUp() {
        val mode = _uiState.value.gameMode ?: return
        statsRepository.recordGameLost(mode, currentVariant())
        _uiState.value = SolitaireUiState()
    }

    // --- Klondike ---
    // Bodies live in SolitaireKlondikeOps.kt; these keep the public/interface API.

    fun newKlondikeGame(config: GameConfig) = newKlondikeGameImpl(config)

    override fun drawFromStock() = drawFromStockImpl()

    fun klondikeMoveWasteToTableau(columnIndex: Int) = klondikeMoveWasteToTableauImpl(columnIndex)

    fun klondikeMoveWasteToFoundation(foundationIndex: Int) = klondikeMoveWasteToFoundationImpl(foundationIndex)

    fun klondikeMoveTableauToFoundation(fromColumn: Int, foundationIndex: Int) =
        klondikeMoveTableauToFoundationImpl(fromColumn, foundationIndex)

    fun klondikeMoveTableauToTableau(fromColumn: Int, cardIndex: Int, toColumn: Int) =
        klondikeMoveTableauToTableauImpl(fromColumn, cardIndex, toColumn)

    override fun klondikeAutoComplete() = klondikeAutoCompleteImpl()

    // --- Spider ---
    // Bodies live in SolitaireSpiderOps.kt; these keep the public/interface API.

    fun newSpiderGame(config: GameConfig) = newSpiderGameImpl(config)

    override fun dealSpiderStock() = dealSpiderStockImpl()

    fun spiderMoveCards(fromColumn: Int, cardIndex: Int, toColumn: Int) =
        spiderMoveCardsImpl(fromColumn, cardIndex, toColumn)

    // --- FreeCell ---
    // Bodies live in SolitaireFreeCellOps.kt; these keep the public API.

    fun newFreeCellGame() = newFreeCellGameImpl()

    fun freeCellMoveToFreeCell(fromColumn: Int, cellIndex: Int) =
        freeCellMoveToFreeCellImpl(fromColumn, cellIndex)

    fun freeCellMoveFromFreeCell(cellIndex: Int, toColumn: Int) =
        freeCellMoveFromFreeCellImpl(cellIndex, toColumn)

    fun freeCellMoveFreeCellToFoundation(cellIndex: Int, foundationIndex: Int) =
        freeCellMoveFreeCellToFoundationImpl(cellIndex, foundationIndex)

    fun freeCellMoveTableauToFoundation(fromColumn: Int, foundationIndex: Int) =
        freeCellMoveTableauToFoundationImpl(fromColumn, foundationIndex)

    fun freeCellMoveTableauToTableau(fromColumn: Int, cardIndex: Int, toColumn: Int) =
        freeCellMoveTableauToTableauImpl(fromColumn, cardIndex, toColumn)

    // --- Pyramid ---
    // Bodies live in SolitairePyramidOps.kt; these keep the public/interface API.

    fun newPyramidGame(config: GameConfig) = newPyramidGameImpl(config)

    /**
     * Tap a card in the pyramid or on the waste. If it forms a valid pair with
     * the current selection (both exposed, or an exposed card plus a card it is
     * the sole cover of), both are removed. Kings (value 13) are removed on their
     * own. Otherwise an exposed tap becomes the new selection.
     */
    override fun pyramidTapCard(id: String) = pyramidTapCardImpl(id)

    override fun pyramidDealStock() = pyramidDealStockImpl()

    // --- Shared ---
    // Drag/drop/auto-move bodies live in SolitaireDragOps.kt; these keep the public/interface API.

    fun tryMoveByDrag(sourceId: String, dropOffset: Offset, cardSize: androidx.compose.ui.unit.IntSize = androidx.compose.ui.unit.IntSize.Zero) =
        tryMoveByDragImpl(sourceId, dropOffset, cardSize)

    // --- Tap to move (auto) ---

    override fun autoMove(sourceId: String) = autoMoveImpl(sourceId)

    /**
     * Starts a drag from [sourceId], deriving the carried cards from current state.
     * Returns false (and starts nothing) when that source holds no card.
     */
    override fun startDrag(sourceId: String, startPos: Offset, cardSize: androidx.compose.ui.unit.IntSize): Boolean =
        startDragImpl(sourceId, startPos, cardSize)

    override fun updateDrag(offset: Offset) = updateDragImpl(offset)

    override fun endDrag(dropOffset: Offset, cardSize: androidx.compose.ui.unit.IntSize) =
        endDragImpl(dropOffset, cardSize)

    override fun cancelDrag() = cancelDragImpl()

    override fun undo() {
        val history = _uiState.value.history
        if (history.isEmpty()) return
        val prev = history.last()
        val newHistory = history.dropLast(1)
        when (prev) {
            is KlondikeState -> _uiState.update {
                it.copy(klondike = prev.copy(usedUndo = true), history = newHistory)
            }
            is SpiderState -> _uiState.update {
                it.copy(spider = prev.copy(usedUndo = true), history = newHistory)
            }
            is FreeCellState -> _uiState.update {
                it.copy(freeCell = prev.copy(usedUndo = true), history = newHistory)
            }
            is PyramidState -> _uiState.update {
                it.copy(pyramid = prev.copy(usedUndo = true), history = newHistory)
            }
        }
    }

    override fun restart() {
        val mode = _uiState.value.gameMode ?: return
        selectMode(mode, currentConfig())
    }

    fun incrementTimer() {
        _uiState.update { state ->
            when (state.gameMode) {
                GameMode.KLONDIKE -> state.copy(
                    klondike = state.klondike?.let { if (!it.isWon) it.copy(elapsedSeconds = it.elapsedSeconds + 1) else it }
                )
                GameMode.SPIDER -> state.copy(
                    spider = state.spider?.let { if (!it.isWon) it.copy(elapsedSeconds = it.elapsedSeconds + 1) else it }
                )
                GameMode.FREECELL -> state.copy(
                    freeCell = state.freeCell?.let { if (!it.isWon) it.copy(elapsedSeconds = it.elapsedSeconds + 1) else it }
                )
                GameMode.PYRAMID -> state.copy(
                    pyramid = state.pyramid?.let { if (!it.isWon) it.copy(elapsedSeconds = it.elapsedSeconds + 1) else it }
                )
                null -> state
            }
        }
    }

    fun dismissAchievementNotification() {
        achievementsManager.dismissNotification()
    }

    internal fun saveHistory() {
        val state = _uiState.value
        val current: Any = when (state.gameMode) {
            GameMode.KLONDIKE -> state.klondike ?: return
            GameMode.SPIDER -> state.spider ?: return
            GameMode.FREECELL -> state.freeCell ?: return
            GameMode.PYRAMID -> state.pyramid ?: return
            null -> return
        }
        _uiState.update { it.copy(history = it.history + current) }
    }

    internal fun onGameWon(mode: GameMode, timeSeconds: Int, moves: Int, usedUndo: Boolean) {
        statsRepository.recordGameWon(mode, currentVariant(), timeSeconds, moves)
        achievementsManager.onAchievementUnlocked("first_win")
        when (mode) {
            GameMode.KLONDIKE -> {
                achievementsManager.onAchievementUnlocked("klondike_first")
                if (timeSeconds < 180) achievementsManager.onAchievementUnlocked("speed_demon")
            }
            GameMode.SPIDER -> achievementsManager.onAchievementUnlocked("spider_first")
            GameMode.FREECELL -> achievementsManager.onAchievementUnlocked("freecell_first")
            GameMode.PYRAMID -> achievementsManager.onAchievementUnlocked("pyramid_first")
        }
        if (!usedUndo) achievementsManager.onAchievementUnlocked("no_undo")
        val totalWins = statsRepository.getTotalGamesWon()
        achievementsManager.onProgressUpdated("wins_10", totalWins)
        achievementsManager.onProgressUpdated("wins_50", totalWins)
        achievementsManager.onProgressUpdated("win_streak_5", statsRepository.getBestWinStreak())
    }
}

