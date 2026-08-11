package com.vayunmathur.flashcards

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.flashcards.data.CardDao
import com.vayunmathur.flashcards.data.DB_NAME
import com.vayunmathur.flashcards.data.DeckDao
import com.vayunmathur.flashcards.data.FlashcardsDatabase
import com.vayunmathur.flashcards.ui.CardEditPage
import com.vayunmathur.flashcards.ui.CardListPage
import com.vayunmathur.flashcards.ui.DeckListPage
import com.vayunmathur.flashcards.ui.ReviewPage
import com.vayunmathur.flashcards.util.FlashcardsViewModel
import com.vayunmathur.flashcards.util.FlashcardsViewModelFactory
import com.vayunmathur.library.room.buildDatabase
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private lateinit var deckDao: DeckDao
    private lateinit var cardDao: CardDao
    private val viewModel: FlashcardsViewModel by viewModels {
        FlashcardsViewModelFactory(application, deckDao, cardDao)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val ready = mutableStateOf(false)
        lifecycleScope.launch(Dispatchers.IO) {
            val db = buildDatabase<FlashcardsDatabase>(dbName = DB_NAME)
            deckDao = db.deckDao()
            cardDao = db.cardDao()
            withContext(Dispatchers.Main) { ready.value = true }
        }

        setContent {
            DynamicTheme {
                if (ready.value) Navigation(viewModel)
            }
        }
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object DeckList : Route

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
    MainNavigation(backStack) {
        entry<Route.DeckList>(metadata = ListPage()) {
            DeckListPage(backStack, viewModel)
        }
        entry<Route.CardList>(metadata = ListDetailPage()) {
            CardListPage(backStack, viewModel, it.deckId)
        }
        entry<Route.CardEdit>(metadata = ListDetailPage()) {
            CardEditPage(backStack, viewModel, it.deckId, it.cardId)
        }
        entry<Route.Review>(metadata = ListDetailPage()) {
            ReviewPage(backStack, viewModel, it.deckId)
        }
    }
}
