package com.vayunmathur.todo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.todo.data.TodoRepository
import com.vayunmathur.todo.platform.TodoViewModel
import com.vayunmathur.todo.platform.TodoViewModelFactory

class MainActivity : ComponentActivity() {
    private lateinit var todoRepository: TodoRepository
    private val todoViewModel: TodoViewModel by viewModels {
        TodoViewModelFactory(application, todoRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        todoRepository = TodoRepository.get(applicationContext)

        setContent {
            DynamicTheme {
                Navigation(todoViewModel)
            }
        }
    }
}
