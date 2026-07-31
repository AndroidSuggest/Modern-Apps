package com.vayunmathur.games.pipes.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.CompletedLevelsRepository
import com.vayunmathur.games.pipes.data.LevelData
import com.vayunmathur.games.pipes.data.LevelPack
import com.vayunmathur.library.util.LevelStats
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PipesGameState(
    val paths: Map<Int, List<CellPos>> = emptyMap(),
    val cellOwner: Map<CellPos, Int> = emptyMap()
)

data class PipesUiState(
    val packIndex: Int = -1,
    val levelIndex: Int = -1,
    val levelData: LevelData? = null,
    val gameState: PipesGameState = PipesGameState(),
    val history: List<PipesGameState> = emptyList(),
    val isLevelWon: Boolean = false,
    val activeColor: Int? = null,
    val activePath: List<CellPos> = emptyList(),
    val preDrawState: PipesGameState? = null
)

class PipesViewModel(application: Application) : AndroidViewModel(application) {

    val repository: CompletedLevelsRepository = CompletedLevelsRepository(application)

    val achievementsManager: AchievementsManager = run {
        val json = application.assets.open("achievements.json")
            .bufferedReader().use { it.readText() }
        PipesAchievementsManager(application, json, repository)
    }

    private val _uiState = MutableStateFlow(PipesUiState())
    val uiState: StateFlow<PipesUiState> = _uiState.asStateFlow()

    private val _levelStats =
        MutableStateFlow<Map<String, LevelStats>>(repository.getLevelStats())
    val levelStats: StateFlow<Map<String, LevelStats>> = _levelStats.asStateFlow()

    private val _nextLevel = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val nextLevel: SharedFlow<Int> = _nextLevel.asSharedFlow()

    private val ds = DataStoreUtils.getInstance(application)

    val colorblind: StateFlow<Boolean> = ds.booleanFlow(KEY_COLORBLIND)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ds.getBoolean(KEY_COLORBLIND, false))

    fun setColorblind(value: Boolean) {
        viewModelScope.launch { ds.setBoolean(KEY_COLORBLIND, value) }
    }

    init {
        viewModelScope.launch {
            achievementsManager.checkExistingAchievements()
        }
    }

    fun loadLevel(packIndex: Int, levelIndex: Int) {
        val current = _uiState.value
        if (current.packIndex == packIndex &&
            current.levelIndex == levelIndex &&
            current.levelData != null
        ) return
        val pack = LevelPack.PACKS[packIndex]
        val levelData = pack.levels[levelIndex]
        _uiState.value = PipesUiState(
            packIndex = packIndex,
            levelIndex = levelIndex,
            levelData = levelData,
            gameState = PipesGameState(),
        )
    }

    fun startDraw(cell: CellPos) {
        val s = _uiState.value
        if (s.isLevelWon || s.levelData == null) return

        val levelData = s.levelData
        val endpointColor = levelData.endpoints.find { ep -> cell in ep.cells }?.colorIndex

        if (endpointColor != null) {
            // Grabbing an endpoint always restarts that color: its current line breaks
            // right away (like grabbing a line partway does) so the drag is free to take
            // a different route. Releasing without drawing leaves the line cleared.
            val brokenState = clearColor(s.gameState, endpointColor)
            _uiState.update {
                it.copy(
                    activeColor = endpointColor,
                    activePath = listOf(cell),
                    gameState = brokenState,
                    preDrawState = it.gameState
                )
            }
            return
        }

        val ownerColor = s.gameState.cellOwner[cell] ?: return
        val path = s.gameState.paths[ownerColor] ?: return
        val idx = path.indexOf(cell)
        if (idx >= 0) {
            _uiState.update { it.copy(activeColor = ownerColor, activePath = path.take(idx + 1), preDrawState = it.gameState) }
        }
    }

    /** Removes [color]'s line from the board, leaving every other color untouched. */
    private fun clearColor(state: PipesGameState, color: Int): PipesGameState {
        val path = state.paths[color] ?: return state
        val newCellOwner = state.cellOwner.toMutableMap()
        path.forEach { c -> if (newCellOwner[c] == color) newCellOwner.remove(c) }
        return PipesGameState(state.paths - color, newCellOwner)
    }

    fun extendPath(cell: CellPos) {
        val s = _uiState.value
        val activeColor = s.activeColor ?: return
        val levelData = s.levelData ?: return
        if (s.isLevelWon) return
        if (cell !in levelData.cells) return

        val currentPath = s.activePath
        if (currentPath.isEmpty()) return

        val pairedEndpoint = levelData.endpoints.find { it.colorIndex == activeColor }
            ?.cells?.let { cells ->
                when (currentPath.first()) {
                    cells[0] -> cells[1]
                    cells[1] -> cells[0]
                    else -> null
                }
            }
        if (pairedEndpoint != null && currentPath.last() == pairedEndpoint) return

        if (currentPath.size >= 2 && cell == currentPath[currentPath.size - 2]) {
            _uiState.update { it.copy(activePath = currentPath.dropLast(1)) }
            return
        }

        if (cell in currentPath) return

        val lastCell = currentPath.last()
        val neighbors = levelData.adjacency[lastCell] ?: return
        if (cell !in neighbors) return

        val otherEndpoints = levelData.endpoints.filter { it.colorIndex != activeColor }
            .flatMap { it.cells }.toSet()
        if (cell in otherEndpoints) return

        val existingOwner = s.gameState.cellOwner[cell]
        if (existingOwner != null && existingOwner != activeColor && cell !in levelData.bridges) {
            // Occupied by another pipe: ignore the movement instead of breaking it.
            // The path stays put until the finger reaches an actually free neighbor.
            return
        }

        _uiState.update { it.copy(activePath = currentPath + cell) }
    }

    fun commitDraw() {
        val s = _uiState.value
        val activeColor = s.activeColor ?: return
        if (s.isLevelWon) return

        val newPath = s.activePath
        val preDrawState = s.preDrawState ?: s.gameState

        if (newPath.size < 2) {
            if (s.gameState != preDrawState) {
                // Tapped an endpoint without drawing: keep the line broken, but make it undoable.
                _uiState.update {
                    it.copy(
                        history = it.history + preDrawState,
                        activeColor = null,
                        activePath = emptyList(),
                        preDrawState = null
                    )
                }
            } else {
                _uiState.update { it.copy(activeColor = null, activePath = emptyList(), gameState = preDrawState, preDrawState = null) }
            }
            return
        }

        val currentState = s.gameState
        val newCellOwner = currentState.cellOwner.toMutableMap()

        currentState.paths[activeColor]?.forEach { c ->
            if (newCellOwner[c] == activeColor) newCellOwner.remove(c)
        }

        val newPaths = currentState.paths.toMutableMap()
        newPaths[activeColor] = newPath
        val bridges = s.levelData?.bridges ?: emptySet()
        for (cell in newPath) {
            if (cell in bridges && newCellOwner[cell] != null && newCellOwner[cell] != activeColor) continue
            newCellOwner[cell] = activeColor
        }

        val newGameState = PipesGameState(newPaths, newCellOwner)

        _uiState.update {
            it.copy(
                gameState = newGameState,
                history = it.history + preDrawState,
                activeColor = null,
                activePath = emptyList(),
                preDrawState = null
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            repository.incrementTotalPipesPlaced()
            achievementsManager.onProgressUpdated("pipes_1000", repository.getTotalPipesPlaced())
        }

        checkWin()
    }

    private fun checkWin() {
        val s = _uiState.value
        val levelData = s.levelData ?: return
        val gameState = s.gameState

        if (gameState.cellOwner.size != levelData.cells.size) return

        val allConnected = levelData.endpoints.all { ep ->
            val path = gameState.paths[ep.colorIndex] ?: return
            path.size >= 2 && setOf(path.first(), path.last()) == ep.cells.toSet()
        }

        if (allConnected) onLevelWon()
    }

    private fun onLevelWon() {
        val s = _uiState.value
        if (s.isLevelWon || s.packIndex < 0) return
        _uiState.update { it.copy(isLevelWon = true) }

        val pack = LevelPack.PACKS[s.packIndex]
        val level = pack.levels[s.levelIndex]
        val moves = getCurrentMoves()

        viewModelScope.launch {
            val refreshed = withContext(Dispatchers.IO) {
                repository.updateBestScore(level.id, moves)
                repository.getLevelStats()
            }
            _levelStats.value = refreshed

            achievementsManager.onAchievementUnlocked("first_flow")
            achievementsManager.onAchievementUnlocked("first_level")
            achievementsManager.onProgressUpdated("level_50", refreshed.size)
            if (moves <= level.optimalMoves) {
                achievementsManager.onAchievementUnlocked("optimal_win")
            }
            val pack0 = LevelPack.PACKS[0]
            val pack0Completed = pack0.levels.count { refreshed.containsKey(it.id) }
            if (pack0Completed >= pack0.levels.size) {
                achievementsManager.onAchievementUnlocked("all_5x5")
            }

            delay(500)
            _nextLevel.emit(s.levelIndex + 1)
        }
    }

    fun getCurrentMoves(): Int = _uiState.value.history.size

    fun onUndo() {
        val s = _uiState.value
        if (s.history.isEmpty() || s.isLevelWon) return
        _uiState.update {
            it.copy(
                gameState = it.history.last(),
                history = it.history.dropLast(1),
                activeColor = null,
                activePath = emptyList()
            )
        }
    }

    fun onRestart() {
        val s = _uiState.value
        if (s.history.isEmpty() || s.isLevelWon || s.packIndex < 0) return
        _uiState.update {
            it.copy(
                gameState = PipesGameState(),
                history = emptyList(),
                isLevelWon = false,
                activeColor = null,
                activePath = emptyList()
            )
        }
    }

    fun dismissAchievementNotification() {
        achievementsManager.dismissNotification()
    }

    companion object {
        const val KEY_COLORBLIND = "pipes_colorblind"
    }
}
