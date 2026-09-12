package com.vayunmathur.games.chess

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object Game: Route
    @Serializable
    data object Puzzles: Route
    @Serializable
    data object Learn: Route
    @Serializable
    data class LearnStage(val categoryKey: String, val stageKey: String): Route
    @Serializable
    data object GameCenter: Route
}
