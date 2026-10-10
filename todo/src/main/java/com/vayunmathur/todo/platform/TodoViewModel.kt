package com.vayunmathur.todo.platform

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TodoViewModel(
    application: Application,
    private val repository: TodoRepository,
) : AndroidViewModel(application) {

    val todos: StateFlow<List<Todo>> = repository.todos
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleDone(todo: Todo) {
        viewModelScope.launch(Dispatchers.IO) { repository.upsert(todo.copy(done = !todo.done)) }
    }

    fun delete(todo: Todo) {
        viewModelScope.launch(Dispatchers.IO) { repository.delete(todo) }
    }

    fun create(title: String, dueAt: Long?, onCreated: (Long) -> Unit) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                repository.upsert(Todo(title = title.trim(), dueAt = dueAt))
            }
            onCreated(id)
        }
    }

    fun upsertAll(todos: List<Todo>) {
        viewModelScope.launch(Dispatchers.IO) { repository.upsertAll(todos) }
    }

    @OptIn(FlowPreview::class)
    @Composable
    fun editableTodo(initialId: Long, default: () -> Todo): MutableState<Todo> {
        var currentId by remember { mutableLongStateOf(initialId) }

        val todoFlow = remember(currentId) { repository.todoByIdFlow(currentId) }
        val dbTodo by todoFlow.collectAsStateWithLifecycle(initialValue = null)

        val localState = remember { mutableStateOf<Todo?>(null) }

        LaunchedEffect(dbTodo, currentId) {
            dbTodo?.let { localState.value = it }
        }

        val pendingWrites = remember { MutableStateFlow<Todo?>(null) }
        LaunchedEffect(Unit) {
            pendingWrites.filterNotNull().debounce(SAVE_DEBOUNCE_MS).collectLatest { newValue ->
                val newId = withContext(Dispatchers.IO) { repository.upsert(newValue) }
                if (currentId == 0L) currentId = newId
            }
        }

        return remember {
            object : MutableState<Todo> {
                override var value: Todo
                    get() = localState.value ?: default()
                    set(newValue) {
                        localState.value = newValue
                        pendingWrites.value = newValue
                    }

                override fun component1(): Todo = value
                override fun component2(): (Todo) -> Unit = { value = it }
            }
        }
    }

    fun deleteById(id: Long, onDeleted: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val todo = repository.getAll().firstOrNull { it.id == id }
            if (todo != null) repository.delete(todo)
            withContext(Dispatchers.Main) { onDeleted() }
        }
    }

    companion object {
        private const val SAVE_DEBOUNCE_MS = 300L
    }
}

/** Factory for constructing [TodoViewModel] with the repository. */
class TodoViewModelFactory(
    private val application: Application,
    private val repository: TodoRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(TodoViewModel::class.java)) { "Unexpected ViewModel class: $modelClass" }
        return TodoViewModel(application, repository) as T
    }
}
