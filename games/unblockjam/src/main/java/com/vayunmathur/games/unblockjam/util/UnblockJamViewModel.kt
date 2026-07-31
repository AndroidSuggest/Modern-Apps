package com.vayunmathur.games.unblockjam.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.unblockjam.data.CompletedLevelsRepository
import com.vayunmathur.games.unblockjam.data.LevelData
import com.vayunmathur.games.unblockjam.data.LevelPack
import com.vayunmathur.library.util.LevelStats
import com.vayunmathur.library.util.AchievementsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI state for the active game screen.
 *
 * [currentLevelData] is null until [UnblockJamViewModel.loadLevel] has been
 * called for the current ([packIndex], [levelIndex]).
 */
data class UnblockJamUiState(
    val packIndex: Int = -1,
    val levelIndex: Int = -1,
    val currentLevelData: LevelData? = null,
    val history: List<LevelData> = emptyList(),
    val isLevelWon: Boolean = false,
)

/**
 * ViewModel for the UnblockJam game.
 *
 * Owns:
 *  - Current level data + move history + win state
 *  - Persistent level stats (best scores, total moves, undo count) via
 *    [CompletedLevelsRepository]
 *  - The [AchievementsManager] instance and existing-achievement check
 *
 * Composables keep only purely-visual state: the in-flight drag offsets,
 * dialog visibility, and the slide-out animation for the main block when
 * a level is won.
 */
class UnblockJamViewModel(application: Application) : AndroidViewModel(application) {

    val repository: CompletedLevelsRepository = CompletedLevelsRepository(application)

    val achievementsManager: AchievementsManager = run {
        val json = application.assets.open("achievements.json")
            .bufferedReader().use { it.readText() }
        UnblockJamAchievementsManager(application, json, repository)
    }

    private val _uiState = MutableStateFlow(UnblockJamUiState())
    val uiState: StateFlow<UnblockJamUiState> = _uiState.asStateFlow()

    private val _levelStats =
        MutableStateFlow<Map<String, LevelStats>>(repository.getLevelStats())
    val levelStats: StateFlow<Map<String, LevelStats>> = _levelStats.asStateFlow()

    init {
        viewModelScope.launch {
            achievementsManager.checkExistingAchievements()
        }
    }

    /**
     * Loads the requested level if it differs from the currently-loaded one.
     * Safe to call from a [androidx.compose.runtime.LaunchedEffect] keyed on
     * ([packIndex], [levelIndex]).
     */
    fun loadLevel(packIndex: Int, levelIndex: Int) {
        val current = _uiState.value
        if (current.packIndex == packIndex &&
            current.levelIndex == levelIndex &&
            current.currentLevelData != null
        ) return
        val pack = LevelPack.PACKS[packIndex]
        _uiState.value = UnblockJamUiState(
            packIndex = packIndex,
            levelIndex = levelIndex,
            currentLevelData = pack.levels[levelIndex],
        )
    }

    /** Move count for the active attempt, including the winning move if applicable. */
    fun getCurrentMoves(): Int {
        val s = _uiState.value
        val winningMoveIncrement =
            if (s.isLevelWon && s.currentLevelData?.lastMovedBlockIndex != 0) 1 else 0
        return s.history.size + winningMoveIncrement
    }

    fun onBlockMoved(newLevelData: LevelData) {
        val s = _uiState.value
        val current = s.currentLevelData ?: return
        // Block moved back to its previous position — collapse with last history entry.
        if (s.history.isNotEmpty() && s.history.last().blocks == newLevelData.blocks) {
            _uiState.update {
                it.copy(
                    currentLevelData = it.history.last(),
                    history = it.history.dropLast(1),
                )
            }
            return
        }
        if (s.isLevelWon) return

        if (newLevelData.lastMovedBlockIndex != current.lastMovedBlockIndex) {
            _uiState.update {
                it.copy(
                    currentLevelData = newLevelData,
                    history = it.history + current,
                )
            }
            viewModelScope.launch(Dispatchers.IO) {
                repository.incrementTotalMoves()
                achievementsManager.onProgressUpdated(
                    "moves_1000", repository.getTotalMoves(),
                )
            }
        } else {
            _uiState.update { it.copy(currentLevelData = newLevelData) }
        }
    }

    fun onLevelWon() {
        val s = _uiState.value
        if (s.isLevelWon || s.packIndex < 0) return
        _uiState.update { it.copy(isLevelWon = true) }

        val pack = LevelPack.PACKS[s.packIndex]
        val level = pack.levels[s.levelIndex]
        val moves = getCurrentMoves()

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.updateBestScore(level.id, moves)
            }
            val refreshed = withContext(Dispatchers.IO) { repository.getLevelStats() }
            _levelStats.value = refreshed

            achievementsManager.onAchievementUnlocked("first_level")
            achievementsManager.onProgressUpdated("level_50", refreshed.size)
            if (moves <= level.optimalMoves) {
                achievementsManager.onAchievementUnlocked("optimal_win")
            }
            if (s.packIndex == 0 && refreshed.size >= pack.levels.size) {
                achievementsManager.onAchievementUnlocked("all_levels_pack_0")
            }
        }
    }

    fun onUndo() {
        val s = _uiState.value
        if (s.history.isEmpty() || s.isLevelWon) return
        _uiState.update {
            it.copy(
                currentLevelData = it.history.last(),
                history = it.history.dropLast(1),
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.incrementUndoCount()
            achievementsManager.onProgressUpdated(
                "undo_master", repository.getUndoCount(),
            )
        }
    }

    fun onRestart() {
        val s = _uiState.value
        if (s.history.isEmpty() || s.isLevelWon || s.packIndex < 0) return
        val pack = LevelPack.PACKS[s.packIndex]
        _uiState.update {
            it.copy(
                currentLevelData = pack.levels[it.levelIndex],
                history = emptyList(),
                isLevelWon = false,
            )
        }
    }

    fun dismissAchievementNotification() {
        achievementsManager.dismissNotification()
    }
}
