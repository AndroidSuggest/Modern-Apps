package com.vayunmathur.games.wordmaker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.games.wordmaker.data.LevelDataStore
import com.vayunmathur.games.wordmaker.platform.WordMakerAchievementsManager
import com.vayunmathur.games.wordmaker.platform.WordMakerViewModel
import com.vayunmathur.games.wordmaker.ui.SettingsPage
import com.vayunmathur.games.wordmaker.ui.WordMakerGameLoader
import com.vayunmathur.library.ui.GameCenterScreen
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.GameHubSessionHook
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.openSettingsIfRequested
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun Navigation(viewModel: WordMakerViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Game)
    backStack.openSettingsIfRequested(Route.Settings)
    GameHubSessionHook("wordmaker", "Wordmaker")
    MainNavigation(backStack) {
        entry<Route.Game>(metadata = FullscreenPage()) {
            WordMakerGameLoader(backStack, viewModel)
        }
        entry<Route.GameCenter> {
            val achievementsManager = rememberAchievementsManager(viewModel.levelDataStore)
            if (achievementsManager != null) {
                GameCenterScreen(
                    manager = achievementsManager,
                    onBack = { backStack.pop() },
                )
            }
        }
        entry<Route.Settings> { SettingsPage(viewModel = viewModel, onBack = { backStack.pop() }) }
    }
}

@Composable
fun rememberAchievementsManager(levelDataStore: LevelDataStore): AchievementsManager? {
    val context = LocalContext.current
    val state = produceState<AchievementsManager?>(initialValue = null, context, levelDataStore) {
        value = withContext(Dispatchers.IO) {
            val json = context.assets.open("achievements.json")
                .bufferedReader()
                .use { it.readText() }
            WordMakerAchievementsManager(context, json, levelDataStore)
        }
    }
    return state.value
}
