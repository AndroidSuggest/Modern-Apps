package com.vayunmathur.flashcards.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ButtonDefaults
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconUndo
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.parseMarkdown

/** Binds an in-memory review session over [deckId] to the stateless [ReviewScreen]. */
@Composable
fun ReviewPage(
    backStack: NavBackStack<Route>,
    viewModel: FlashcardsViewModel,
    deckId: Long,
) {
    LaunchedEffect(deckId) { viewModel.startSession(deckId) }
    val state by viewModel.review.collectAsStateWithLifecycle()

    ReviewScreen(
        state = state,
        actions = object : ReviewActions {
            override fun back() { backStack.pop() }
            override fun grade(grade: Grade) { viewModel.gradeCurrent(grade) }
            override fun undo() { viewModel.undoReview() }
        },
    )
}

/**
 * A single review card, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`. [initialRevealed] seeds the
 * answer-visible state so a preview can capture the graded state without tapping.
 */
@Composable
fun ReviewScreen(
    state: ReviewUiState,
    actions: ReviewActions,
    initialRevealed: Boolean = false,
) {
    var revealed by remember { mutableStateOf(initialRevealed) }
    LaunchedEffect(state.front, state.done) { revealed = initialRevealed }

    AppScaffold(
        title = {
            if (!state.done) {
                Text(
                    stringResource(
                        R.string.review_counts,
                        state.newCount,
                        state.learningCount,
                        state.reviewCount,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
        onNavigateBack = { actions.back() },
        actions = {
            if (state.canUndo) {
                IconButton(onClick = { actions.undo() }) { IconUndo() }
            }
        },
    ) { paddingValues ->
        if (state.done) {
            EmptyState(
                title = stringResource(R.string.review_done),
                message = stringResource(R.string.review_done_hint),
                modifier = Modifier.fillMaxSize().padding(paddingValues),
            )
            return@AppScaffold
        }

        Column(Modifier.fillMaxSize().padding(paddingValues)) {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth(),
            )
            FlipCard(
                front = state.front,
                back = state.back,
                revealed = revealed,
                modifier = Modifier.weight(1f).fillMaxWidth().clickable { revealed = true },
            )
            if (revealed) {
                GradeButtons(state = state, onGrade = { actions.grade(it) })
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
private fun FlipCard(
    front: String,
    back: String,
    revealed: Boolean,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (revealed) 180f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "flip",
    )
    Box(
        modifier = modifier.graphicsLayer {
            rotationY = rotation
            cameraDistance = 12f * density
        },
        contentAlignment = Alignment.Center,
    ) {
        if (rotation <= 90f) {
            CardFace {
                Text(
                    parseMarkdown(front, showMarkers = false),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            // Counter-rotate so the back face reads correctly.
            Box(Modifier.graphicsLayer { rotationY = 180f }) {
                CardFace {
                    Text(
                        parseMarkdown(front, showMarkers = false),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    HorizontalDivider(Modifier.padding(vertical = 20.dp))
                    Text(
                        parseMarkdown(back, showMarkers = false),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun CardFace(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}

@Composable
private fun GradeButtons(state: ReviewUiState, onGrade: (Grade) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GradeButton(R.string.grade_again, GRADE_AGAIN_COLOR, state.label(Grade.AGAIN), Grade.AGAIN, onGrade, Modifier.weight(1f))
        GradeButton(R.string.grade_hard, GRADE_HARD_COLOR, state.label(Grade.HARD), Grade.HARD, onGrade, Modifier.weight(1f))
        GradeButton(R.string.grade_good, GRADE_GOOD_COLOR, state.label(Grade.GOOD), Grade.GOOD, onGrade, Modifier.weight(1f))
        GradeButton(R.string.grade_easy, GRADE_EASY_COLOR, state.label(Grade.EASY), Grade.EASY, onGrade, Modifier.weight(1f))
    }
}

@Composable
private fun GradeButton(
    labelRes: Int,
    color: Color,
    interval: String,
    grade: Grade,
    onGrade: (Grade) -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = { onGrade(grade) },
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(labelRes), style = MaterialTheme.typography.labelLarge)
            if (interval.isNotEmpty()) {
                Text(interval, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private val GRADE_AGAIN_COLOR = Color(0xFFD32F2F)
private val GRADE_HARD_COLOR = Color(0xFFF57C00)
private val GRADE_GOOD_COLOR = Color(0xFF388E3C)
private val GRADE_EASY_COLOR = Color(0xFF1976D2)
