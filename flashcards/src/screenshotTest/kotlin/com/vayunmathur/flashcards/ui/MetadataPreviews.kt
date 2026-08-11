package com.vayunmathur.flashcards.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.flashcards.data.Card
import com.vayunmathur.flashcards.data.Deck
import com.vayunmathur.flashcards.util.CardListActions
import com.vayunmathur.flashcards.util.CardListUiState
import com.vayunmathur.flashcards.util.DeckListActions
import com.vayunmathur.flashcards.util.DeckListUiState
import com.vayunmathur.flashcards.util.DeckSummary
import com.vayunmathur.flashcards.util.ReviewActions
import com.vayunmathur.flashcards.util.ReviewUiState
import com.vayunmathur.library.ui.DynamicTheme

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store listing images for `:flashcards`. See `common-conventions-preview-metadata`.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 *
 * Everything here is a literal — no ViewModel, no database, no device — which is also what
 * makes the images reproducible from a clean checkout.
 */
class MetadataPreviews {

    private val deckSamples = listOf(
        DeckSummary(Deck(id = 1, name = "Spanish Vocabulary", position = 0.0), dueCount = 12, totalCount = 84),
        DeckSummary(Deck(id = 2, name = "World Capitals", position = 1.0), dueCount = 5, totalCount = 50),
        DeckSummary(Deck(id = 3, name = "Kotlin Idioms", position = 2.0), dueCount = 0, totalCount = 30),
        DeckSummary(Deck(id = 4, name = "Anatomy 101", position = 3.0), dueCount = 23, totalCount = 120),
        DeckSummary(Deck(id = 5, name = "Guitar Chords", position = 4.0), dueCount = 3, totalCount = 18),
    )

    private val cardSamples = listOf(
        Card(id = 1, deckId = 1, front = "la manzana", back = "the apple"),
        Card(id = 2, deckId = 1, front = "el perro", back = "the dog"),
        Card(id = 3, deckId = 1, front = "la biblioteca", back = "the library"),
        Card(id = 4, deckId = 1, front = "el aeropuerto", back = "the airport"),
        Card(id = 5, deckId = 1, front = "la playa", back = "the beach"),
    )

    @PreviewTest
    @Preview(name = "1-decks", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Decks() {
        DynamicTheme(darkTheme = true) {
            DeckListScreen(
                state = DeckListUiState(decks = deckSamples),
                actions = DeckListActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-review", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Review() {
        DynamicTheme(darkTheme = true) {
            ReviewScreen(
                state = ReviewUiState(
                    front = "la biblioteca",
                    back = "the library",
                    remaining = 12,
                    done = false,
                ),
                actions = ReviewActions.Noop,
                initialRevealed = true,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-cards", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Cards() {
        DynamicTheme(darkTheme = true) {
            CardListScreen(
                state = CardListUiState(
                    deckName = "Spanish Vocabulary",
                    cards = cardSamples,
                    dueCount = 12,
                ),
                actions = CardListActions.Noop,
            )
        }
    }
}
