package com.vayunmathur.communicate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconSms
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.communicate.ui.CallLogsScreen
import com.vayunmathur.communicate.ui.ConversationScreen
import com.vayunmathur.communicate.ui.DialerScreen
import com.vayunmathur.communicate.ui.MessagesScreen
import kotlinx.serialization.Serializable

sealed interface Route : NavKey {
    @Serializable data object Messages : Route
    @Serializable data object Dialer : Route
    @Serializable data object CallLogs : Route
    @Serializable data class Conversation(val threadId: Long, val address: String) : Route
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DynamicTheme {
                CommunicateApp()
            }
        }
    }
}

@Composable
private fun CommunicateApp() {
    val backStack = rememberNavBackStack<Route>(Route.Messages)
    val currentPage = backStack.last()
    val currentRoot = when (currentPage) {
        is Route.Conversation -> Route.Messages
        else -> currentPage
    }
    val pages = listOf(
        BottomBarItem(stringResource(R.string.nav_messages), Route.Messages) { IconSms() },
        BottomBarItem(stringResource(R.string.nav_dialer), Route.Dialer) { IconCall() },
        BottomBarItem(stringResource(R.string.nav_call_logs), Route.CallLogs) { IconHistory() },
    )

    MainNavigation(
        backStack = backStack,
        bottomBar = {
            if (currentPage !is Route.Conversation) {
                BottomNavBar(backStack, pages, currentRoot)
            }
        },
    ) {
        entry<Route.Messages> {
            MessagesScreen(
                onOpenThread = { thread ->
                    backStack.add(Route.Conversation(thread.threadId, thread.address))
                },
            )
        }
        entry<Route.Dialer> { DialerScreen() }
        entry<Route.CallLogs> { CallLogsScreen() }
        entry<Route.Conversation> { route ->
            ConversationScreen(
                threadId = route.threadId,
                address = route.address,
                onBack = { backStack.pop() },
            )
        }
    }
}
