package com.vayunmathur.todo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.todo.Route
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.platform.TodoActions
import com.vayunmathur.todo.platform.TodoUiState
import com.vayunmathur.todo.platform.TodoViewModel

/** Binds [TodoViewModel] and the nav back stack to the stateless [TodoDetailScreen]. */
@Composable
fun TodoDetailPage(backStack: NavBackStack<Route>, viewModel: TodoViewModel, id: Long) {
    val todoState = viewModel.editableTodo(id) { Todo(title = "") }

    val actions = remember(backStack, viewModel, id, todoState) {
        object : TodoActions {
            override fun back() { backStack.pop() }
            override fun setTitle(title: String) { todoState.value = todoState.value.copy(title = title) }
            override fun setDone(done: Boolean) { todoState.value = todoState.value.copy(done = done) }
            override fun setDueAt(dueAt: Long?) { todoState.value = todoState.value.copy(dueAt = dueAt) }
            override fun deleteTodo() { viewModel.deleteById(id) { backStack.pop() } }
        }
    }

    val todo = todoState.value
    TodoDetailScreen(
        state = TodoUiState(title = todo.title, done = todo.done, dueAt = todo.dueAt),
        actions = actions,
    )
}
