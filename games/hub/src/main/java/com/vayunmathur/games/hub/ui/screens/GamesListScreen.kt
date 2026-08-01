package com.vayunmathur.games.hub.ui.screens

import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.ui.components.GameCard
import com.vayunmathur.games.hub.util.GameIconResolver
import com.vayunmathur.games.hub.util.GamesListActions
import com.vayunmathur.games.hub.util.GamesListUiState
import com.vayunmathur.games.hub.viewmodel.GameHubViewModel
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.hub.R

enum class GameSort { LAST_PLAYED, MOST_PLAYED, NAME, COMPLETION }

/** Binds [GameHubViewModel] to the stateless [GamesListScreen]. */
@Composable
fun GamesListPage(
    viewModel: GameHubViewModel,
    onGameClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val games by viewModel.gamesFlow.collectAsStateWithLifecycle()
    val allAchievements by viewModel.allAchievementsFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val iconCache = remember { mutableMapOf<String, Drawable?>() }

    val installedGameIds = remember(games) {
        games.filter { g ->
            try { context.packageManager.getPackageInfo(g.packageName, 0); true } catch (_: Exception) { false }
        }.mapTo(mutableSetOf()) { it.gameId }
    }

    val achievementProgressByGame = remember(allAchievements) {
        allAchievements.groupBy { it.gameId }.mapValues { (_, list) -> list.count { it.isUnlocked } to list.size }
    }

    GamesListScreen(
        state = GamesListUiState(
            games = games,
            achievementProgressByGame = achievementProgressByGame,
            installedGameIds = installedGameIds,
        ),
        actions = object : GamesListActions {
            override fun openGame(gameId: String) = onGameClick(gameId)
            override fun playGame(game: HubGameEntity) = launchGame(context, game)
        },
        iconFor = { game -> iconCache.getOrPut(game.packageName) { GameIconResolver.resolveAppIcon(context, game.packageName) } },
        modifier = modifier,
    )
}

/**
 * The games list, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview`. Search and sort stay here: they are the screen's own state, and filtering the
 * list is pure. [iconFor] is the one thing that needs a device — see [DashboardScreen].
 */
@Composable
fun GamesListScreen(
    state: GamesListUiState,
    actions: GamesListActions,
    modifier: Modifier = Modifier,
    iconFor: (HubGameEntity) -> Drawable? = { null },
) {
    var search by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(GameSort.LAST_PLAYED) }
    var showSortMenu by remember { mutableStateOf(false) }

    val filteredSorted = remember(state.games, search, sort, state.achievementProgressByGame) {
        var list = if (search.isBlank()) state.games else state.games.filter { it.displayName.contains(search, true) || it.gameId.contains(search, true) }
        when (sort) {
            GameSort.LAST_PLAYED -> list.sortedByDescending { it.lastPlayedAt ?: 0L }
            GameSort.MOST_PLAYED -> list.sortedByDescending { it.totalPlaytimeMs }
            GameSort.NAME -> list.sortedBy { it.displayName }
            GameSort.COMPLETION -> list.sortedByDescending { game ->
                val (unlocked, total) = state.achievementProgressByGame[game.gameId] ?: (0 to 1)
                if (total == 0) 0f else unlocked.toFloat() / total.toFloat().coerceAtLeast(1f)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_games)) },
                actions = {
                    IconButton(onClick = { showSortMenu = true }) { IconMoreVert() }
                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.sort_last_played)) }, onClick = { sort = GameSort.LAST_PLAYED; showSortMenu = false })
                        DropdownMenuItem(text = { Text(stringResource(R.string.sort_most_played)) }, onClick = { sort = GameSort.MOST_PLAYED; showSortMenu = false })
                        DropdownMenuItem(text = { Text(stringResource(R.string.sort_name)) }, onClick = { sort = GameSort.NAME; showSortMenu = false })
                        DropdownMenuItem(text = { Text(stringResource(R.string.sort_completion)) }, onClick = { sort = GameSort.COMPLETION; showSortMenu = false })
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { CommonSearchBar(value = search, onValueChange = { search = it }, placeholder = stringResource(com.vayunmathur.games.hub.R.string.search_games), padding = PaddingValues(0.dp)) }
            if (filteredSorted.isEmpty()) {
                item { Text(text = if (search.isNotEmpty()) stringResource(R.string.no_matching_games) else stringResource(R.string.no_games_registered_yet_nplay_a_game_to), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 32.dp)) }
            }
            items(filteredSorted, key = { it.gameId }) { game ->
                GameCard(
                    game = game, isInstalled = game.gameId in state.installedGameIds,
                    achievementProgress = state.achievementProgressByGame[game.gameId],
                    iconDrawable = iconFor(game),
                    onClick = { actions.openGame(game.gameId) },
                    onPlay = { actions.playGame(game) }
                )
            }
        }
    }
}
