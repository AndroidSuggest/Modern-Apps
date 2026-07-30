package com.vayunmathur.calculator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.calculator.ui.CalculatorPage
import com.vayunmathur.calculator.ui.GraphPage
import com.vayunmathur.calculator.util.CalculatorViewModel
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconCalculate
import com.vayunmathur.library.ui.IconFunctions
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private val viewModel: CalculatorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                Navigation(viewModel)
            }
        }
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Calculator : Route

    @Serializable
    data object Graph : Route
}

@Composable
fun Navigation(viewModel: CalculatorViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Calculator)
    val current = backStack.last()
    MainNavigation(
        backStack,
        bottomBar = {
            BottomNavBar(
                backStack,
                listOf(
                    BottomBarItem("Calculator", Route.Calculator) { IconCalculate() },
                    BottomBarItem("Graph", Route.Graph) { IconFunctions() },
                ),
                current,
            )
        },
    ) {
        entry<Route.Calculator> { CalculatorPage(viewModel) }
        entry<Route.Graph> { GraphPage(viewModel) }
    }
}
