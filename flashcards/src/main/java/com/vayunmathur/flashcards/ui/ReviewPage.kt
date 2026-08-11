package com.vayunmathur.flashcards.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.flashcards.R
import com.vayunmathur.flashcards.Route
import com.vayunmathur.flashcards.util.FlashcardsViewModel
import com.vayunmathur.flashcards.util.Grade
import com.vayunmathur.flashcards.util.ReviewActions
import com.vayunmathur.flashcards.util.ReviewUiState
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.NavBackStack

/**
 * Binds a review session over the due cards of [deckId] to the stateless [ReviewScreen].
 * The due-cards flow uses a fixed `now` cutoff, so grading a card advances its due date
 * past the cutoff and it drops out of the queue, revealing the next card.
 */
@Composable
fun ReviewPage(
    backStack: NavBackStack<Route>,
    viewModel: FlashcardsViewModel,
    deckId: Long,
) {
    val due by remember(deckId) { viewModel.dueCardsFor(deckId) }
        .collectAsStateWithLifecycle(emptyList())

    val current = due.firstOrNull()

    ReviewScreen(
        state = ReviewUiState(
            front = current?.front ?: "",
            back = current?.back ?: "",
            remaining = due.size,
            done = current == null,
        ),
        actions = object : ReviewActions {
            override fun back() { backStack.pop() }
            override fun grade(grade: Grade) {
                current?.let { viewModel.gradeCard(it, grade) }
            }
        },
    )
}

/**
 * A single review card, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`. [initialRevealed] seeds the
 * answer-visible state so a preview can capture the graded state without tapping.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    state: ReviewUiState,
    actions: ReviewActions,
    initialRevealed: Boolean = false,
) {
    var revealed by remember { mutableStateOf(initialRevealed) }
    // Reset to the question side whenever a new card comes up.
    LaunchedEffect(state.front, state.done) { revealed = initialRevealed }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { if (!state.done) Text(state.remaining.toString()) },
                navigationIcon = { IconNavigation { actions.back() } },
            )
        },
    ) { paddingValues ->
        if (state.done) {
            Box(
                Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.review_done),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(paddingValues)) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    state.front,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                if (revealed) {
                    HorizontalDivider(Modifier.padding(vertical = 24.dp))
                    Text(
                        state.back,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (revealed) {
                GradeButtons(onGrade = { actions.grade(it) })
            } else {
                Button(
                    onClick = { revealed = true },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Text(stringResource(R.string.show_answer))
                }
            }
        }
    }
}

@Composable
private fun GradeButtons(onGrade: (Grade) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GradeButton(R.string.grade_again, Grade.AGAIN, onGrade, Modifier.weight(1f))
        GradeButton(R.string.grade_hard, Grade.HARD, onGrade, Modifier.weight(1f))
        GradeButton(R.string.grade_good, Grade.GOOD, onGrade, Modifier.weight(1f))
        GradeButton(R.string.grade_easy, Grade.EASY, onGrade, Modifier.weight(1f))
    }
}

@Composable
private fun GradeButton(
    labelRes: Int,
    grade: Grade,
    onGrade: (Grade) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(onClick = { onGrade(grade) }, modifier = modifier) {
        Text(stringResource(labelRes))
    }
}
