package com.vayunmathur.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.library.ui.Tab
import com.vayunmathur.library.ui.TabRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.share.ui.ShareReceiveScreen
import com.vayunmathur.share.ui.ShareSendScreen
import com.vayunmathur.share.ui.ShareViewModel
import com.vayunmathur.share.ui.ShareViewModelFactory
import com.vayunmathur.share.util.SharePermissions

class MainActivity : ComponentActivity() {

    private val shareViewModel: ShareViewModel by viewModels { ShareViewModelFactory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Cold-start: route ACTION_SEND / SEND_MULTIPLE into the Send flow.
        handleIncomingShareIntent(intent)
        setContent {
            DynamicTheme {
                PermissionsChecker(
                    permissions = SharePermissions.allSharePermissions(),
                    text = stringResource(R.string.share_permission_rationale),
                ) {
                    ShareApp(shareViewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> shareViewModel.handleShareIntent(intent)
        }
    }
}

private enum class ShareTab { Receive, Send }

@Composable
private fun ShareApp(viewModel: ShareViewModel) {
    var currentTab by remember { mutableStateOf(ShareTab.Receive) }
    // If launched from a share intent, start on Send.
    val outgoing by viewModel.outgoingUris.collectAsState()
    LaunchedEffect(outgoing) {
        if (outgoing.isNotEmpty()) currentTab = ShareTab.Send
    }

    AppScaffold(title = stringResource(R.string.app_name)) { padding ->
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = currentTab.ordinal) {
                Tab(
                    selected = currentTab == ShareTab.Receive,
                    onClick = { currentTab = ShareTab.Receive },
                    text = { Text(stringResource(R.string.tab_receive)) },
                )
                Tab(
                    selected = currentTab == ShareTab.Send,
                    onClick = { currentTab = ShareTab.Send },
                    text = { Text(stringResource(R.string.tab_send)) },
                )
            }
            when (currentTab) {
                ShareTab.Receive -> ShareReceiveScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                ShareTab.Send -> ShareSendScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
