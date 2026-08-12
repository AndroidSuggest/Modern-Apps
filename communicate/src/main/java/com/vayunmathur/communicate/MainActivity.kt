package com.vayunmathur.communicate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceSession
import com.vayunmathur.communicate.data.googlevoice.call.CallPhase
import com.vayunmathur.communicate.data.googlevoice.call.GoogleVoiceCallManager
import com.vayunmathur.communicate.telephony.GoogleVoiceTelecom
import com.vayunmathur.communicate.ui.AccountsScreen
import com.vayunmathur.communicate.ui.CallLogsScreen
import com.vayunmathur.communicate.ui.ConversationScreen
import com.vayunmathur.communicate.ui.DialerScreen
import com.vayunmathur.communicate.ui.MessagesScreen
import com.vayunmathur.communicate.ui.call.CallScreen
import com.vayunmathur.communicate.ui.googlevoice.GoogleVoiceSignInScreen
import androidx.compose.ui.platform.LocalContext
import kotlinx.serialization.Serializable

sealed interface Route : NavKey {
    @Serializable data object Messages : Route
    @Serializable data object Dialer : Route
    @Serializable data object CallLogs : Route
    @Serializable data object Accounts : Route
    @Serializable data object GoogleVoiceSignIn : Route

    @Serializable
    data class Conversation(
        val threadId: Long,
        val address: String,
        // Serialized as the enum name; defaults keep older back-stack entries valid.
        val line: CommunicateLine = CommunicateLine.Sim,
        val remoteId: String? = null,
        val subscriptionId: Int? = null,
    ) : Route
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
    val context = LocalContext.current
    val session = remember { GoogleVoiceSession.get(context) }
    val gvSignedIn by session.signedInFlow.collectAsState(initial = false)
    val callState by GoogleVoiceCallManager.state.collectAsState()

    // Make Google Voice a selectable line and route inbound SIP INVITEs to the system.
    LaunchedEffect(gvSignedIn) {
        GoogleVoiceCallManager.init(context)
        GoogleVoiceCallManager.onIncomingCall = { from -> GoogleVoiceTelecom.addIncoming(context, from) }
        if (gvSignedIn) {
            val number = session.phoneNumber() ?: context.getString(R.string.account_google_voice)
            GoogleVoiceTelecom.registerPhoneAccount(context, number)
        }
    }

    val backStack = rememberNavBackStack<Route>(Route.Messages)
    val currentPage = backStack.last()
    val currentRoot = when (currentPage) {
        is Route.Conversation -> Route.Messages
        is Route.Accounts, is Route.GoogleVoiceSignIn -> Route.Messages
        else -> currentPage
    }
    val pages = listOf(
        BottomBarItem(stringResource(R.string.nav_messages), Route.Messages) { IconSms() },
        BottomBarItem(stringResource(R.string.nav_dialer), Route.Dialer) { IconCall() },
        BottomBarItem(stringResource(R.string.nav_call_logs), Route.CallLogs) { IconHistory() },
    )

    val showBottomBar = currentPage is Route.Messages ||
        currentPage is Route.Dialer ||
        currentPage is Route.CallLogs

    MainNavigation(
        backStack = backStack,
        bottomBar = {
            if (showBottomBar) {
                BottomNavBar(backStack, pages, currentRoot)
            }
        },
    ) {
        entry<Route.Messages> {
            MessagesScreen(
                onOpenThread = { thread ->
                    backStack.add(
                        Route.Conversation(
                            threadId = thread.threadId,
                            address = thread.address,
                            line = thread.line,
                            remoteId = thread.remoteId,
                            subscriptionId = thread.subscriptionId,
                        ),
                    )
                },
                onOpenAccounts = { backStack.add(Route.Accounts) },
            )
        }
        entry<Route.Dialer> { DialerScreen() }
        entry<Route.CallLogs> { CallLogsScreen() }
        entry<Route.Accounts> {
            AccountsScreen(
                onBack = { backStack.pop() },
                onSignIn = { backStack.add(Route.GoogleVoiceSignIn) },
            )
        }
        entry<Route.GoogleVoiceSignIn> {
            GoogleVoiceSignInScreen(
                onBack = { backStack.pop() },
                onSignedIn = { backStack.pop() },
            )
        }
        entry<Route.Conversation> { route ->
            ConversationScreen(
                threadId = route.threadId,
                address = route.address,
                line = route.line,
                remoteId = route.remoteId,
                subscriptionId = route.subscriptionId,
                onBack = { backStack.pop() },
            )
        }
    }

    // In-app call UI overlays everything while a Google Voice VoIP call is in progress.
    if (callState.phase != CallPhase.Idle) {
        CallScreen(onClose = { GoogleVoiceCallManager.clearEnded() })
    }
}
