package com.vayunmathur.todo

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.todo.data.TodoRepository
import com.vayunmathur.todo.platform.TodoViewModel
import com.vayunmathur.todo.platform.TodoViewModelFactory

class MainActivity : MaActivity() {
    private lateinit var todoRepository: TodoRepository
    private val todoViewModel: TodoViewModel by viewModels {
        TodoViewModelFactory(application, todoRepository)
    }

    override fun onCreateBeforeContent() {
        todoRepository = TodoRepository.get(applicationContext)
    }

    @Composable
    override fun Content() {
        Navigation(todoViewModel)
    }
}
