// RAW ANIMATION EXCEPTION: the rejection-shake is an imperative motion - a rejected word is
// walked through a hand-written sequence of decaying offsets, which is not a state a
// declarative animation can settle on.
package com.vayunmathur.games.wordmaker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.wordmaker.data.CrosswordData
import com.vayunmathur.games.wordmaker.platform.WordGameActions
import com.vayunmathur.games.wordmaker.ui.components.WordToAnimate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Word-submission decision table for the letter wheel, extracted from
 * [WordGameScreen] to keep that file under the length limit.
 * Behavior identical — only moved.
 */
fun wordSubmitHandler(
    crosswordData: CrosswordData,
    foundWords: Set<String>,
    bonusWords: Set<String>,
    wordToAnimate: WordToAnimate?,
    animatedWord: String?,
    actions: WordGameActions,
    scope: CoroutineScope,
    animationProgress: Animatable<Float, AnimationVector1D>,
    density: Density,
    wordShakeAnim: Animatable<Float, AnimationVector1D>,
    bonusShakeAnim: Animatable<Float, AnimationVector1D>,
    onAnimateSolution: (WordToAnimate) -> Unit,
    onAnimateBonus: (String?) -> Unit,
): suspend CoroutineScope.(String, List<Int>) -> Unit = { word, ids ->
    suspend fun shakeAnim(anim: Animatable<Float, AnimationVector1D>, duration: Int = 40) {
        for (o in listOf(-16f, 12f, -8f, 6f, -3f, 0f)) {
            anim.animateTo(with(density) { o.dp.toPx() }, tween(duration))
        }
    }

    val isSolution = word in crosswordData.solutionWords
    val isBonus = !isSolution && word.length >= 3 && actions.isInDictionary(word)

    // #543 — re-entering an already-found/bonus word must not
    // replay the board/bonus animation. Check for duplicates
    // first, including words that are mid-animation (not yet
    // flushed to DataStore + foundWords/bonusWords flow).
    val alreadyFound = word in foundWords || word == wordToAnimate?.word
    val alreadyBonus = word in bonusWords || word == animatedWord
    when {
        isSolution && alreadyFound -> shakeAnim(wordShakeAnim)
        isBonus && alreadyBonus -> {
            val j = launch { shakeAnim(bonusShakeAnim, 60) }
            shakeAnim(wordShakeAnim)
            j.join()
        }
        isSolution && word !in foundWords -> {
            onAnimateSolution(WordToAnimate(word, ids))
            actions.onSolutionWordFound(word)
        }
        isBonus && word !in bonusWords -> {
            scope.launch {
                onAnimateBonus(word)
                animationProgress.snapTo(0f)
                animationProgress.animateTo(1f, tween(800))
                actions.addBonusWord(word)
                onAnimateBonus(null)
            }
        }
        else -> shakeAnim(wordShakeAnim)
    }
}
