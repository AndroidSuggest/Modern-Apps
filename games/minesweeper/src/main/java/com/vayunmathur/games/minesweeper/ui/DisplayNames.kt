package com.vayunmathur.games.minesweeper.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.minesweeper.R
import com.vayunmathur.games.minesweeper.data.BoardSize
import com.vayunmathur.games.minesweeper.data.Difficulty
import com.vayunmathur.library.ui.R as UiR

/**
 * Display names live here rather than on the enums so `data` stays free of Android types and the
 * enums can be used from the generator and its unit tests without a resource lookup.
 */
@Composable
fun BoardSize.displayName(): String = stringResource(
    when (this) {
        BoardSize.SMALL -> R.string.size_small
        BoardSize.MEDIUM -> UiR.string.medium
        BoardSize.LARGE -> R.string.size_large
    }
)

@Composable
fun Difficulty.displayName(): String = stringResource(
    when (this) {
        Difficulty.EASY -> UiR.string.easy
        Difficulty.MEDIUM -> UiR.string.medium
        Difficulty.HARD -> UiR.string.hard
        Difficulty.EXPERT -> R.string.difficulty_expert
    }
)
