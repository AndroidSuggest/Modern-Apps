package com.vayunmathur.games.wordmaker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.wordmaker.R
import com.vayunmathur.games.wordmaker.data.CrosswordData
import com.vayunmathur.games.wordmaker.data.WheelSpacing
import com.vayunmathur.games.wordmaker.platform.WordGameActions
import com.vayunmathur.games.wordmaker.ui.components.AnimatedLetter
import com.vayunmathur.games.wordmaker.ui.components.CompetitiveStatusBar
import com.vayunmathur.games.wordmaker.ui.components.DailyStatusBar
import com.vayunmathur.games.wordmaker.ui.components.LetterChooser
import com.vayunmathur.games.wordmaker.ui.components.WordToAnimate
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.FilledIconButton
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.Text
import kotlinx.coroutines.CoroutineScope

/**
 * Status bars plus the letter wheel below the board, extracted from
 * [WordGameScreen] to keep that file under the length limit.
 * Behavior identical — only moved.
 */
@Composable
fun BoxScope.WordGameWheel(
    isCompetitive: Boolean,
    isDaily: Boolean,
    competitiveScore: Int,
    remainingTimeMs: Long,
    dailyStreak: Long,
    wheelHeight: Dp,
    isWon: Boolean,
    timedOut: Boolean,
    currentLevel: Int,
    actions: WordGameActions,
    shuffledLetters: List<ChooserLetter>,
    onShuffle: () -> Unit,
    tapToSpell: Boolean,
    wheelSpacing: WheelSpacing,
    crosswordData: CrosswordData,
    foundWords: Set<String>,
    bonusWords: Set<String>,
    wordToAnimate: WordToAnimate?,
    animatedWord: String?,
    scope: CoroutineScope,
    animationProgress: Animatable<Float, AnimationVector1D>,
    density: Density,
    wordShakeAnim: Animatable<Float, AnimationVector1D>,
    bonusShakeAnim: Animatable<Float, AnimationVector1D>,
    onAnimateSolution: (WordToAnimate) -> Unit,
    onAnimateBonus: (String?) -> Unit,
    onWordBoxPositioned: (Offset) -> Unit,
    letterChooserPositions: Map<Int, Offset>,
    onLetterPositioned: (Int, Offset) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isCompetitive) {
            CompetitiveStatusBar(
                score = competitiveScore,
                remainingTimeMs = remainingTimeMs
            )
        } else if (isDaily) {
            DailyStatusBar(streak = dailyStreak)
        }
        Spacer(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier.height(wheelHeight),
            contentAlignment = Alignment.Center
        ) {
            if (isWon && isDaily) {
                Text(
                    stringResource(R.string.daily_come_back_tomorrow),
                    fontWeight = FontWeight.Bold
                )
            } else if (isWon && !isCompetitive) {
                Button(onClick = { actions.saveLevel(currentLevel + 1) }) {
                    Text(stringResource(R.string.next_level))
                }
            } else if (isCompetitive && (isWon || timedOut)) {
                // Level finished — the between-levels lobby (WordMakerGameLoader) takes over.
            } else {
                LetterChooser(
                    letters = shuffledLetters,
                    tapToSpell = tapToSpell,
                    wheelSpacing = wheelSpacing,
                    onShuffle = onShuffle,
                    onWordSubmitted = wordSubmitHandler(
                        crosswordData = crosswordData,
                        foundWords = foundWords,
                        bonusWords = bonusWords,
                        wordToAnimate = wordToAnimate,
                        animatedWord = animatedWord,
                        actions = actions,
                        scope = scope,
                        animationProgress = animationProgress,
                        density = density,
                        wordShakeAnim = wordShakeAnim,
                        bonusShakeAnim = bonusShakeAnim,
                        onAnimateSolution = onAnimateSolution,
                        onAnimateBonus = onAnimateBonus,
                    ),
                    onWordBoxPositioned = onWordBoxPositioned,
                    onLetterPositioned = { id, offset ->
                        if (letterChooserPositions[id] != offset) {
                            onLetterPositioned(id, offset)
                        }
                    },
                    wordShakeTranslation = wordShakeAnim.value
                )
            }
        }
    }
}

/**
 * Corner buttons (bonus words, hint) with their cooldown indicator, extracted
 * from [WordGameScreen] to keep that file under the length limit.
 * Behavior identical — only moved.
 */
@Composable
fun BoxScope.WordGameCornerButtons(
    isWon: Boolean,
    isCompetitive: Boolean,
    bonusWords: Set<String>,
    bonusShake: Float,
    onOpenBonusWords: () -> Unit,
    onBonusButtonPositioned: (Offset) -> Unit,
    remainingCooldownMs: Long,
    onOpenHint: () -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(16.dp)
            .padding(bottom = 32.dp)
            .onGloballyPositioned { onBonusButtonPositioned(it.localToRoot(Offset.Zero)) }
            .graphicsLayer {
                translationX = bonusShake
            },
    ) {
        FilledIconButton(
            onClick = onOpenBonusWords,
            enabled = bonusWords.isNotEmpty()
        ) {
            Icon(
                painterResource(R.drawable.outline_book_2_24), null
            )
        }
    }

    if (!isWon && !isCompetitive) {
        val hintEnabled = remainingCooldownMs <= 0L
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            FilledIconButton(
                onClick = onOpenHint,
                enabled = hintEnabled
            ) {
                Icon(
                    painterResource(android.R.drawable.ic_menu_help),
                    contentDescription = stringResource(R.string.cd_hint),
                    modifier = Modifier.graphicsLayer { alpha = if (hintEnabled) 1f else 0.5f }
                )
            }
            if (!hintEnabled) {
                CircularProgressIndicator(
                    progress = { 1f - (remainingCooldownMs / 30_000f) },
                    modifier = Modifier.size(48.dp).align(Alignment.Center),
                    strokeWidth = 3.dp
                )
            }
        }
    }
}
