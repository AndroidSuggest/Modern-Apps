package com.vayunmathur.todo

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object TodoList : Route

    @Serializable
    data class TodoDetail(val id: Long) : Route
}
