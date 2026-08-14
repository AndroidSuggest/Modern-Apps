package com.vayunmathur.flashcards

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.flashcards.data.CardDao
import com.vayunmathur.flashcards.data.DB_NAME
import com.vayunmathur.flashcards.data.DeckDao
import com.vayunmathur.flashcards.data.FlashcardsDatabase
import com.vayunmathur.flashcards.data.ReviewLogDao
import com.vayunmathur.flashcards.ui.CardEditPage
import com.vayunmathur.flashcards.ui.CardListPage
import com.vayunmathur.flashcards.ui.DeckListPage
import com.vayunmathur.flashcards.ui.ReviewPage
import com.vayunmathur.flashcards.ui.SettingsPage
import com.vayunmathur.flashcards.ui.StatsPage
import com.vayunmathur.flashcards.util.FlashcardsViewModel
import com.vayunmathur.flashcards.util.FlashcardsViewModelFactory
import com.vayunmathur.flashcards.util.ThemeMode
import com.vayunmathur.library.room.buildDatabase
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconDashboard
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconStyle
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private lateinit var deckDao: DeckDao
    private lateinit var cardDao: CardDao
    private lateinit var reviewLogDao: ReviewLogDao
    private val viewModel: FlashcardsViewModel by viewModels {
        FlashcardsViewModelFactory(application, deckDao, cardDao, reviewLogDao)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val ready = mutableStateOf(false)
        lifecycleScope.launch(Dispatchers.IO) {
            val db = buildDatabase<FlashcardsDatabase>(dbName = DB_NAME)
            deckDao = db.deckDao()
            cardDao = db.cardDao()
            reviewLogDao = db.reviewLogDao()
            withContext(Dispatchers.Main) { ready.value = true }
        }

        setContent {
            if (ready.value) {
                val settings by viewModel.settings.collectAsStateWithLifecycle()
                val darkTheme = when (settings.themeMode) {
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                    else -> null
                }
                DynamicTheme(darkTheme = darkTheme) { Navigation(viewModel) }
            } else {
                DynamicTheme {}
            }
        }
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object DeckList : Route

    @Serializable
    data object Stats : Route

    @Serializable
    data object Settings : Route

    @Serializable
    data class CardList(val deckId: Long) : Route

    @Serializable
    data class CardEdit(val deckId: Long, val cardId: Long) : Route

    @Serializable
    data class Review(val deckId: Long) : Route
}

@Composable
fun Navigation(viewModel: FlashcardsViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.DeckList)
    MainNavigation(
        backStack = backStack,
        bottomBar = {
            val current = backStack.last()
            if (current is Route.DeckList || current is Route.Stats || current is Route.Settings) {
                BottomNavBar(
                    backStack = backStack,
                    pages = listOf(
                        BottomBarItem(stringResource(R.string.nav_decks), Route.DeckList) { IconStyle() },
                        BottomBarItem(stringResource(R.string.nav_stats), Route.Stats) { IconDashboard() },
                        BottomBarItem(stringResource(R.string.nav_settings), Route.Settings) { IconSettings() },
                    ),
                    currentPage = current,
                )
            }
        },
    ) {
        entry<Route.DeckList> { DeckListPage(backStack, viewModel) }
        entry<Route.Stats> { StatsPage(backStack, viewModel) }
        entry<Route.Settings> { SettingsPage(backStack, viewModel) }
        entry<Route.CardList> { CardListPage(backStack, viewModel, it.deckId) }
        entry<Route.CardEdit> { CardEditPage(backStack, viewModel, it.deckId, it.cardId) }
        entry<Route.Review> { ReviewPage(backStack, viewModel, it.deckId) }
    }
}
