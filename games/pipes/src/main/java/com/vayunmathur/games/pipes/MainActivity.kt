package com.vayunmathur.games.pipes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconArrowForward
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.games.pipes.data.DailyLevels
import com.vayunmathur.games.pipes.data.LevelPack
import com.vayunmathur.games.pipes.ui.GameBoard
import com.vayunmathur.games.pipes.ui.PipesTheme
import com.vayunmathur.games.pipes.util.AppBackupAgent
import com.vayunmathur.games.pipes.util.DailyProgress
import com.vayunmathur.games.pipes.util.GameBoardUiState
import com.vayunmathur.games.pipes.util.PackProgress
import com.vayunmathur.games.pipes.util.PipesActions
import com.vayunmathur.games.pipes.util.PipesGameState
import com.vayunmathur.games.pipes.util.PipesViewModel
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.ui.GameCenterScreen
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.util.openSettingsIfRequested
import com.vayunmathur.library.util.GameHubComposeHook
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LevelPack.init(this)
        setContent {
            PipesTheme {
                val viewModel: PipesViewModel = viewModel()
                Navigation(viewModel)
            }
        }
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object PackSelector : Route

    @Serializable
    data class LevelSelector(val packIndex: Int) : Route

    @Serializable
    data class Game(val packIndex: Int, val levelIndex: Int) : Route

    @Serializable
    data object DailySelector : Route

    @Serializable
    data class DailyGame(val levelIndex: Int) : Route

    @Serializable
    data object GameCenter : Route

    @Serializable
    data object Settings : Route
}

@Composable
fun Navigation(viewModel: PipesViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.PackSelector)
    // Land on settings when opened from the system App Info page.
    backStack.openSettingsIfRequested(Route.Settings)
    val newAchievement by viewModel.achievementsManager.newAchievement.collectAsState()

    GameHubComposeHook("pipes", viewModel.achievementsManager)

    Box(Modifier.fillMaxSize()) {
        MainNavigation(backStack) {
            entry<Route.PackSelector> {
                PackScreen(backStack, viewModel, onOpenGameCenter = { backStack.add(Route.GameCenter) })
            }
            entry<Route.LevelSelector> {
                LevelScreen(backStack, viewModel, it.packIndex)
            }
            entry<Route.Game> {
                GameScreen(backStack, viewModel, it.packIndex, it.levelIndex)
            }
            entry<Route.DailySelector> {
                DailyLevelScreen(backStack, viewModel)
            }
            entry<Route.DailyGame> {
                GameScreen(backStack, viewModel, PipesViewModel.DAILY_PACK_INDEX, it.levelIndex)
            }
            entry<Route.GameCenter> {
                GameCenterScreen(
                    backupAgent = AppBackupAgent(),
                    manager = viewModel.achievementsManager,
                    onBack = { backStack.pop() }
                )
            }
            entry<Route.Settings> {
                SettingsScreen(backStack, viewModel)
            }
        }

        newAchievement?.let {
            AchievementNotification(it) {
                viewModel.dismissAchievementNotification()
            }
        }
    }
}

/** Binds [PipesViewModel] to the stateless [PackListScreen]. */
@Composable
fun PackScreen(backStack: NavBackStack<Route>, viewModel: PipesViewModel, onOpenGameCenter: () -> Unit) {
    val levelStats by viewModel.levelStats.collectAsState()
    val dailyCompleted by viewModel.dailyCompleted.collectAsState()
    val dailyDay by viewModel.dailyDay.collectAsState()
    val dailyStreak by viewModel.dailyStreak.collectAsState()

    PackListScreen(
        daily = DailyProgress(
            day = dailyDay,
            completed = dailyCompleted,
            total = DailyLevels.LEVELS_PER_DAY,
            streak = dailyStreak,
        ),
        packs = LevelPack.PACKS.map { pack ->
            PackProgress(
                name = pack.name,
                shape = pack.shape,
                completed = pack.levels.count { levelStats.containsKey(it.id) },
                total = pack.levels.size,
            )
        },
        onOpenDaily = { backStack.add(Route.DailySelector) },
        onOpenPack = { backStack.add(Route.LevelSelector(it)) },
        onOpenSettings = { backStack.add(Route.Settings) },
        onOpenGameCenter = onOpenGameCenter,
    )
}

/**
 * The pack selector, with no dependency on the ViewModel or on the asset-loaded packs so it
 * can be rendered from a `@Preview` — see `src/screenshotTest`, which is where the store
 * listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackListScreen(
    packs: List<PackProgress>,
    onOpenPack: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGameCenter: () -> Unit,
    daily: DailyProgress? = null,
    onOpenDaily: () -> Unit = {},
) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.pack_selector)) },
            actions = {
                IconButton(onClick = onOpenSettings) {
                    IconSettings()
                }
                IconButton(onClick = onOpenGameCenter) {
                    Icon(painterResource(id = android.R.drawable.btn_star_big_on), "Achievements")
                }
            }
        )
    }) { paddingValues ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = paddingValues + PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 0.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            daily?.let { item { DailyCard(it, onOpenDaily) } }
            itemsIndexed(packs) { index, pack ->
                Card(
                    Modifier.clickable { onOpenPack(index) },
                    colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                pack.name,
                                style = MaterialTheme.typography.headlineMedium
                            )
                            Text(
                                pack.shape.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        Text(
                            "${pack.completed}/${pack.total}",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyCard(daily: DailyProgress, onOpen: () -> Unit) {
    Card(
        Modifier.clickable { onOpen() },
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    stringResource(R.string.daily_challenge),
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    formatEpochDay(daily.day),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${daily.completed}/${daily.total}",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(R.string.daily_streak, daily.streak.toInt()),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

private fun formatEpochDay(day: Long): String = LocalDate.ofEpochDay(day)
    .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/** The 5 levels of today's daily pack, reusing the badge logic of the pack level grid. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyLevelScreen(backStack: NavBackStack<Route>, viewModel: PipesViewModel) {
    val dailyPack by viewModel.dailyPack.collectAsState()
    val dailyStats by viewModel.dailyStats.collectAsState()

    // Generates on first open, and again if the day rolled over while the app was backgrounded.
    LaunchedEffect(Unit) { viewModel.refreshDaily() }
    Scaffold(topBar = {
        TopAppBar(
            { Text(stringResource(R.string.daily_challenge)) },
            navigationIcon = { IconNavigation(backStack) }
        )
    }) { paddingValues ->
        val levels = dailyPack?.levels
        if (levels == null) {
            Box(Modifier.fillMaxSize().padding(paddingValues), Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyVerticalGrid(
            GridCells.Adaptive(88.dp),
            Modifier.fillMaxSize(),
            contentPadding = paddingValues + PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 0.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(levels) { index, levelData ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable { backStack.add(Route.DailyGame(index)) },
                    colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)
                ) {
                    Box(Modifier.fillMaxSize().padding(8.dp)) {
                        Text("${index + 1}", Modifier.align(Alignment.Center))
                        val levelStat = dailyStats[levelData.id]
                        Box(
                            Modifier
                                .size(20.dp)
                                .align(Alignment.CenterEnd),
                            Alignment.Center
                        ) {
                            when {
                                levelStat == null -> return@Box
                                levelStat.bestScore <= levelData.optimalMoves -> IconStar()
                                else -> IconCheck()
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LevelScreen(backStack: NavBackStack<Route>, viewModel: PipesViewModel, packIndex: Int) {
    val pack = LevelPack.PACKS[packIndex]
    val levelStats by viewModel.levelStats.collectAsState()
    Scaffold(topBar = {
        TopAppBar(
            { Text(stringResource(R.string.level_selector)) },
            navigationIcon = { IconNavigation(backStack) }
        )
    }) { paddingValues ->
        LazyVerticalGrid(
            GridCells.Adaptive(88.dp),
            Modifier.fillMaxSize(),
            contentPadding = paddingValues + PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 0.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(pack.levels) { index, levelData ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable { backStack.add(Route.Game(packIndex, index)) },
                    colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)
                ) {
                    Box(Modifier.fillMaxSize().padding(8.dp)) {
                        if (levelData.cells.size < levelData.rows * levelData.cols) {
                            LevelThumbnail(
                                levelData,
                                Modifier
                                    .size(24.dp)
                                    .align(Alignment.CenterStart)
                            )
                        }
                        Text("${index + 1}", Modifier.align(Alignment.Center))
                        val levelStat = levelStats[levelData.id]
                        Box(
                            Modifier
                                .size(20.dp)
                                .align(Alignment.CenterEnd),
                            Alignment.Center
                        ) {
                            when {
                                levelStat == null -> return@Box
                                levelStat.bestScore <= levelData.optimalMoves -> IconStar()
                                else -> IconCheck()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Binds [PipesViewModel] to the stateless [GameBoardScreen] and loads the level. */
@Composable
fun GameScreen(backStack: NavBackStack<Route>, viewModel: PipesViewModel, packIndex: Int, levelIndex: Int) {
    val uiState by viewModel.uiState.collectAsState()
    val packStats by viewModel.levelStats.collectAsState()
    val dailyStats by viewModel.dailyStats.collectAsState()
    val dailyPack by viewModel.dailyPack.collectAsState()
    val colorblind by viewModel.colorblind.collectAsState()

    val isDaily = packIndex == PipesViewModel.DAILY_PACK_INDEX
    val levels = if (isDaily) dailyPack?.levels else LevelPack.PACKS[packIndex].levels
    val levelStats = if (isDaily) dailyStats else packStats

    // Restoring straight onto a daily level can outrun pack generation.
    LaunchedEffect(isDaily) { if (isDaily) viewModel.refreshDaily() }

    LaunchedEffect(packIndex, levelIndex, levels) {
        viewModel.loadLevel(packIndex, levelIndex)
    }

    val levelData = levels?.getOrNull(levelIndex)
    if (levels == null || levelData == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val isReady = uiState.packIndex == packIndex &&
            uiState.levelIndex == levelIndex &&
            uiState.levelData != null
    val currentLevelStats = levelStats[levelData.id]

    GameBoardScreen(
        state = GameBoardUiState(
            levelData = if (isReady) uiState.levelData!! else levelData,
            levelIndex = levelIndex,
            maxLevelIndex = levels.lastIndex,
            gameState = if (isReady) uiState.gameState else PipesGameState(),
            activeColor = if (isReady) uiState.activeColor else null,
            activePath = if (isReady) uiState.activePath else emptyList(),
            isLevelWon = isReady && uiState.isLevelWon,
            isCompleted = currentLevelStats != null,
            colorblind = colorblind,
            moves = if (isReady) viewModel.getCurrentMoves() else 0,
            bestScore = currentLevelStats?.bestScore,
            canUndo = isReady && uiState.history.isNotEmpty(),
        ),
        actions = viewModel,
        onBack = { backStack.pop() },
        onLevelChange = { newIndex ->
            val clamped = newIndex.coerceIn(0, levels.lastIndex)
            backStack.setLast(
                if (isDaily) Route.DailyGame(clamped) else Route.Game(packIndex, clamped)
            )
        },
    )
}

/**
 * The board screen, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameBoardScreen(
    state: GameBoardUiState,
    actions: PipesActions,
    onBack: () -> Unit,
    onLevelChange: (Int) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar({}, navigationIcon = { IconNavigation(onBack) }) },
        floatingActionButton = {
            // Quick "next level" shortcut once solved, for players who want to move on fast.
            if (state.isLevelWon && state.levelIndex < state.maxLevelIndex) {
                FloatingActionButton(onClick = { onLevelChange(state.levelIndex + 1) }) {
                    IconArrowForward()
                }
            }
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            val infoBoxes = @Composable {
                PuzzleInfoBox(
                    levelIndex = state.levelIndex,
                    onLevelChange = onLevelChange,
                    isCompleted = state.isCompleted,
                    maxLevelIndex = state.maxLevelIndex
                )
                MovesInfoBox(
                    moves = state.moves,
                    bestScore = state.bestScore,
                    optimalMoves = state.levelData.optimalMoves
                )
            }
            val actionButtons = @Composable {
                // Kept in the layout (invisible + disabled) once solved so the board doesn't
                // shift; neither button does anything on a won level.
                val enabled = state.canUndo && !state.isLevelWon
                val hiddenWhenWon = Modifier.alpha(if (state.isLevelWon) 0f else 1f)
                Button(
                    onClick = { actions.onUndo() },
                    modifier = hiddenWhenWon,
                    enabled = enabled
                ) {
                    Text(stringResource(UiR.string.undo))
                }
                Button(
                    onClick = { actions.onRestart() },
                    modifier = hiddenWhenWon,
                    enabled = enabled
                ) {
                    Text(stringResource(R.string.restart))
                }
            }
            val board = @Composable { boardModifier: Modifier ->
                GameBoard(
                    levelData = state.levelData,
                    gameState = state.gameState,
                    activeColor = state.activeColor,
                    activePath = state.activePath,
                    onStartDraw = actions::startDraw,
                    onExtendPath = actions::extendPath,
                    onCommitDraw = actions::commitDraw,
                    isLevelWon = state.isLevelWon,
                    colorblind = state.colorblind,
                    modifier = boardModifier
                )
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
            ) {
                if (maxWidth > maxHeight) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            board(Modifier.fillMaxSize())
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            infoBoxes()
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                actionButtons()
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            infoBoxes()
                        }
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            board(Modifier.fillMaxSize())
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            actionButtons()
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(backStack: NavBackStack<Route>, viewModel: PipesViewModel) {
    val colorblind by viewModel.colorblind.collectAsState()
    Scaffold(topBar = {
        TopAppBar(
            { Text(stringResource(UiR.string.settings)) },
            navigationIcon = { IconNavigation(backStack) }
        )
    }) { paddingValues ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.colorblind_mode),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.colorblind_mode_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(checked = colorblind, onCheckedChange = { viewModel.setColorblind(it) })
            }
        }
    }
}

@Composable
fun LevelThumbnail(levelData: com.vayunmathur.games.pipes.data.LevelData, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(modifier) {
        val maxDim = maxOf(levelData.rows, levelData.cols)
        if (maxDim == 0 || levelData.cells.isEmpty()) return@Canvas
        val cell = size.minDimension / maxDim
        val minRow = levelData.cells.minOf { it.row }
        val minCol = levelData.cells.minOf { it.col }
        val usedRows = levelData.cells.maxOf { it.row } - minRow + 1
        val usedCols = levelData.cells.maxOf { it.col } - minCol + 1
        val offX = (size.width - usedCols * cell) / 2f - minCol * cell
        val offY = (size.height - usedRows * cell) / 2f - minRow * cell
        for (c in levelData.cells) {
            drawRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(offX + c.col * cell, offY + c.row * cell),
                size = androidx.compose.ui.geometry.Size(cell * 0.85f, cell * 0.85f)
            )
        }
    }
}

@Composable
fun PuzzleInfoBox(levelIndex: Int, onLevelChange: (Int) -> Unit, isCompleted: Boolean, maxLevelIndex: Int) {
    InfoBox(title = stringResource(R.string.level)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(
                onClick = { onLevelChange(levelIndex - 1) },
                enabled = levelIndex > 0
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back_24px),
                    contentDescription = stringResource(R.string.previous_level),
                )
            }
            Text(
                text = "${levelIndex + 1}",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(
                onClick = { onLevelChange(levelIndex + 1) },
                enabled = levelIndex < maxLevelIndex
            ) {
                Icon(
                    painterResource(R.drawable.arrow_forward_24px),
                    contentDescription = stringResource(R.string.next_level),
                )
            }
        }
        if (isCompleted) {
            Text(
                text = stringResource(R.string.completed),
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun MovesInfoBox(moves: Int, bestScore: Int?, optimalMoves: Int) {
    InfoBox(title = stringResource(R.string.moves)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$moves",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${bestScore ?: "-"} / $optimalMoves",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun InfoBox(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.size(width = 150.dp, height = 120.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceAround
        ) {
            Text(text = title, fontSize = 16.sp)
            content()
        }
    }
}
