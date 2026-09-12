package com.vayunmathur.games.chess

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.games.chess.util.AppBackupAgent
import com.vayunmathur.games.chess.util.ChessViewModel
import com.vayunmathur.games.chess.util.LearnViewModel
import com.vayunmathur.games.chess.util.PuzzleViewModel
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.GameCenterScreen
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.GameHubComposeHook
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DynamicTheme {
                val backStack = rememberNavBackStack<Route>(Route.Game)
                val achievementsManager = rememberAchievementsManager()
                GameHubComposeHook("chess", achievementsManager)
                if (achievementsManager == null) {
                    Box(Modifier.fillMaxSize())
                    return@DynamicTheme
                }
                val newAchievement by achievementsManager.newAchievement.collectAsState()

                LaunchedEffect(Unit) {
                    achievementsManager.checkExistingAchievements()
                }

                Box(Modifier.fillMaxSize()) {
                    val pages: List<BottomBarItem<out Route>> = listOf(
                        BottomBarItem(
                            stringResource(R.string.tab_play),
                            Route.Game,
                        ) { IconPlay() },
                        BottomBarItem(
                            stringResource(R.string.tab_puzzles),
                            Route.Puzzles,
                        ) { Icon(painterResource(R.drawable.chess_knight_fill1_24px), null) },
                        BottomBarItem(
                            stringResource(R.string.tab_learn),
                            Route.Learn,
                        ) { Icon(painterResource(R.drawable.school_24px), null) }
                    )
                    MainNavigation(
                        backStack,
                        bottomBar = {
                            val cur = backStack.last()
                            if (cur is Route.Game || cur is Route.Puzzles || cur is Route.Learn) {
                                BottomNavBar(backStack, pages, cur)
                            }
                        }
                    ) {
                        entry<Route.Game>(metadata = SiblingPage()) {
                            val viewModel: ChessViewModel = viewModel()
                            var showNewGameDialog by remember { mutableStateOf(false) }
                            val aiAvailable by viewModel.aiAvailable.collectAsState()

                            ChessGame(
                                viewModel = viewModel,
                                onNewGame = { showNewGameDialog = true },
                                onOpenGameCenter = { backStack.add(Route.GameCenter) },
                                achievementsManager = achievementsManager
                            )

                            if (showNewGameDialog) {
                                NewGameDialog(
                                    onNewGame = {
                                        viewModel.onNewGame(it)
                                        showNewGameDialog = false
                                    },
                                    aiAvailable = aiAvailable
                                )
                            }
                        }
                        entry<Route.Puzzles>(metadata = SiblingPage()) {
                            val puzzleViewModel: PuzzleViewModel = viewModel()
                            PuzzleScreen(puzzleViewModel)
                        }
                        entry<Route.Learn>(metadata = SiblingPage()) {
                            LearnHomeScreen(
                                onOpenStage = { cat, stage ->
                                    backStack.add(Route.LearnStage(cat, stage))
                                }
                            )
                        }
                        entry<Route.LearnStage> { route ->
                            val learnViewModel: LearnViewModel = viewModel()
                            LaunchedEffect(route.categoryKey, route.stageKey) {
                                learnViewModel.loadStage(route.categoryKey, route.stageKey)
                            }
                            LearnStageScreen(
                                viewModel = learnViewModel,
                                onBack = { backStack.pop() },
                                onOpenStage = { cat, stage ->
                                    backStack.setLast(Route.LearnStage(cat, stage))
                                }
                            )
                        }
                        entry<Route.GameCenter> {
                            GameCenterScreen(
                                backupAgent = AppBackupAgent(),
                                manager = achievementsManager,
                                onBack = { backStack.pop() }
                            )
                        }
                    }

                    newAchievement?.let {
                        AchievementNotification(it) {
                            achievementsManager.dismissNotification()
                        }
                    }
                }
            }
        }
    }

}
