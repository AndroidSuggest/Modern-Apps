package com.vayunmathur.todo.platform

import com.vayunmathur.todo.data.Todo

/**
 * The UI contract between [TodoViewModel] plus the nav back stack and the screens.
 *
 * Screens take a state value and an actions interface rather than the ViewModel itself, so
 * they can be rendered by a `@Preview` — which is what the store listing images are
 * generated from. It lives in `platform` rather than `ui` so the dependency runs one way:
 * `ui` depends on `platform`, and the binders in `ui` implement these interfaces.
 */

/** Everything the todo list draws. */
data class TodoListUiState(
    val todos: List<Todo> = emptyList(),
    /** Hidden while a todo is already open beside the list in the list-detail layout. */
    val showAddButton: Boolean = true,
)

/**
 * Todo list callbacks. Every method has a no-op default so a preview can render the screen
 * without supplying behaviour — [Noop] is the whole implementation a preview needs.
 */
interface TodoListActions {
    fun openTodo(id: Long) {}
    fun createTodo() {}
    fun toggleDone(todo: Todo) {}
    fun delete(todo: Todo) {}

    /** Persists a reordered list; each todo already carries its new position. */
    fun upsertAll(todos: List<Todo>) {}

    companion object {
        val Noop: TodoListActions = object : TodoListActions {}
    }
}

/** Everything the todo editor draws. */
data class TodoUiState(
    val title: String = "",
    val done: Boolean = false,
    val dueAt: Long? = null,
)

/** Todo editor callbacks. Same no-op-default arrangement as [TodoListActions]. */
interface TodoActions {
    fun back() {}
    fun setTitle(title: String) {}
    fun setDone(done: Boolean) {}
    fun setDueAt(dueAt: Long?) {}
    fun deleteTodo() {}

    companion object {
        val Noop: TodoActions = object : TodoActions {}
    }
}
