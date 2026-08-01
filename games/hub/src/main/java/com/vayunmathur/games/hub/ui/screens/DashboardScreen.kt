package com.vayunmathur.games.hub.ui.screens

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.ui.components.ActivityItemCard
import com.vayunmathur.games.hub.ui.components.GameCard
import com.vayunmathur.games.hub.ui.components.LevelBadge
import com.vayunmathur.games.hub.ui.components.StatCard
import com.vayunmathur.games.hub.ui.components.StreakCard
import com.vayunmathur.games.hub.ui.components.XpProgressBar
import com.vayunmathur.games.hub.util.DashboardActions
import com.vayunmathur.games.hub.util.DashboardUiState
import com.vayunmathur.games.hub.util.GameIconResolver
import com.vayunmathur.games.hub.util.formatPlaytime
import com.vayunmathur.games.hub.viewmodel.GameHubViewModel
import com.vayunmathur.library.ui.BackupButtons
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.hub.R

/** Binds [GameHubViewModel] to the stateless [DashboardScreen]. */
@Composable
fun DashboardPage(
    viewModel: GameHubViewModel,
    onGameClick: (String) -> Unit,
    onProfileClick: () -> Unit,
    onActivityClick: () -> Unit,
    onGamesClick: () -> Unit,
    dbConfigs: List<Pair<String, String>>,
    datastoreNames: List<String>,
    modifier: Modifier = Modifier
) {
    val games by viewModel.gamesFlow.collectAsStateWithLifecycle()
    val crossStats by viewModel.statsFlow.collectAsStateWithLifecycle()
    val xp by viewModel.totalXpFlow.collectAsStateWithLifecycle()
    val level by viewModel.levelFlow.collectAsStateWithLifecycle()
    val title by viewModel.titleFlow.collectAsStateWithLifecycle()
    val profile by viewModel.profileFlow.collectAsStateWithLifecycle()
    val recentActivity by viewModel.recentActivityFlow.collectAsStateWithLifecycle()
    val allAchievements by viewModel.allAchievementsFlow.collectAsStateWithLifecycle()
    val sessions by viewModel.sessionsFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val iconCache = remember { mutableMapOf<String, Drawable?>() }

    val installedGameIds = remember(games) {
        games.filter { g ->
            try { context.packageManager.getPackageInfo(g.packageName, 0); true } catch (_: Exception) { false }
        }.mapTo(mutableSetOf()) { it.gameId }
    }

    val recentlyPlayedGames = remember(sessions, games) {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        sessions.filter { it.startTime >= cutoff }
            .distinctBy { it.gameId }
            .mapNotNull { session -> games.find { g -> g.gameId == session.gameId } }
            .take(10)
    }

    val achievementProgressByGame = remember(allAchievements) {
        allAchievements.groupBy { it.gameId }.mapValues { (_, list) -> list.count { it.isUnlocked } to list.size }
    }

    DashboardScreen(
        state = DashboardUiState(
            playerName = profile?.displayName,
            level = level,
            title = title,
            totalXp = xp,
            stats = crossStats,
            recentlyPlayed = recentlyPlayedGames,
            recentActivity = recentActivity,
            achievementProgressByGame = achievementProgressByGame,
            installedGameIds = installedGameIds,
        ),
        actions = object : DashboardActions {
            override fun openGame(gameId: String) = onGameClick(gameId)
            override fun openProfile() = onProfileClick()
            override fun openActivity() = onActivityClick()
            override fun openGamesList() = onGamesClick()
            override fun playGame(game: HubGameEntity) = launchGame(context, game)
        },
        iconFor = { game -> iconCache.getOrPut(game.packageName) { GameIconResolver.resolveAppIcon(context, game.packageName) } },
        topBarActions = { BackupButtons(dbConfigs = dbConfigs, datastoreNames = datastoreNames) },
        modifier = modifier,
    )
}

/**
 * The dashboard, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 *
 * [iconFor] and [topBarActions] are the two things that genuinely need a device: installed
 * app icons, and the backup menu's file pickers (which need an Activity). Both default to
 * nothing so a preview can leave them out.
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    actions: DashboardActions,
    modifier: Modifier = Modifier,
    iconFor: (HubGameEntity) -> Drawable? = { null },
    topBarActions: @Composable RowScope.() -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                actions = topBarActions
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            item {
                Card(onClick = actions::openProfile, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LevelBadge(level = state.level, large = true)
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(text = state.playerName ?: stringResource(R.string.player), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(text = state.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            XpProgressBar(totalXp = state.totalXp, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            if (state.stats.currentStreak > 0 || state.stats.longestStreak > 0) {
                item { StreakCard(currentStreak = state.stats.currentStreak, longestStreak = state.stats.longestStreak, modifier = Modifier.fillMaxWidth()) }
            }

            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(label = stringResource(com.vayunmathur.games.hub.R.string.playtime), value = formatPlaytime(state.stats.totalPlaytimeMs), modifier = Modifier.weight(1f))
                    StatCard(label = stringResource(com.vayunmathur.games.hub.R.string.tab_games), value = "${state.stats.totalGames}", modifier = Modifier.weight(1f))
                    StatCard(label = stringResource(com.vayunmathur.games.hub.R.string.achievements_for), value = "${state.stats.totalAchievementsUnlocked}/${state.stats.totalAchievements}", modifier = Modifier.weight(1f))
                }
            }

            if (state.recentlyPlayed.isNotEmpty()) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.continue_playing), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        TextButton(onClick = actions::openGamesList) { Text(stringResource(R.string.see_all)) }
                    }
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.recentlyPlayed, key = { it.gameId }) { game ->
                            GameCard(
                                game = game, isInstalled = game.gameId in state.installedGameIds,
                                achievementProgress = state.achievementProgressByGame[game.gameId],
                                iconDrawable = iconFor(game),
                                onClick = { actions.openGame(game.gameId) },
                                onPlay = { actions.playGame(game) },
                                modifier = Modifier.fillParentMaxWidth(0.85f)
                            )
                        }
                    }
                }
            }

            if (state.recentActivity.isNotEmpty()) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.recent_activity), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        TextButton(onClick = actions::openActivity) { Text(stringResource(R.string.see_all)) }
                    }
                }
                items(state.recentActivity.take(5), key = { it.id }) { event -> ActivityItemCard(event = event, onGameClick = actions::openGame) }
            } else {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.no_activity_yet_play_a_game), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

fun launchGame(context: Context, game: HubGameEntity) {
    try {
        val intent = context.packageManager.getLaunchIntentForPackage(game.packageName)
        if (intent != null) context.startActivity(intent)
    } catch (_: Exception) { }
}
