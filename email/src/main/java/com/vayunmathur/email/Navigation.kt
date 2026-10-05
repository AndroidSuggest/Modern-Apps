package com.vayunmathur.email

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.email.data.EmailFolder
import com.vayunmathur.email.data.accountColor
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.OutboxEntry
import com.vayunmathur.email.platform.EmailViewModel
import com.vayunmathur.email.platform.IntentState
import com.vayunmathur.email.ui.AddAccountScreen
import com.vayunmathur.email.ui.ComposerScreen
import com.vayunmathur.email.ui.DraftsScreen
import com.vayunmathur.email.ui.EmlViewerScreen
import com.vayunmathur.email.ui.FolderList
import com.vayunmathur.email.ui.MessageListPage
import com.vayunmathur.email.ui.MessageThreadPage
import com.vayunmathur.email.ui.OutboxScreen
import com.vayunmathur.email.ui.SettingsScreen
import com.vayunmathur.library.ui.DrawerState
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.util.EntryProviderScope
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconInbox
import com.vayunmathur.library.ui.IconMail
import com.vayunmathur.library.ui.IconSend
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalDrawerSheet
import com.vayunmathur.library.ui.ModalNavigationDrawer
import com.vayunmathur.library.ui.NavigationDrawerItem
import com.vayunmathur.library.ui.NavigationDrawerItemDefaults
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.contentColorOn
import com.vayunmathur.library.ui.rememberDrawerState
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.openSettingsIfRequested
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.coroutines.launch
import com.vayunmathur.library.ui.R as UiR

@Composable
fun Navigation(viewModel: EmailViewModel) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val context = LocalContext.current

    val accounts by viewModel.accounts.collectAsStateWithLifecycle(emptyList())
    val selectedAccountEmail by viewModel.selectedAccountEmail.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle(emptyList())
    val selectedFolderName by viewModel.selectedFolderName.collectAsStateWithLifecycle()
    val outbox by viewModel.outbox.collectAsStateWithLifecycle(emptyList())

    val backStack = rememberNavBackStack<Route>(Route.MessageList)
    backStack.openSettingsIfRequested(Route.Settings)

    val navigationRoute = IntentState.navigationRoute
    LaunchedEffect(navigationRoute) {
        if (navigationRoute != null) {
            backStack.add(navigationRoute)
            IntentState.navigationRoute = null
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                EmailDrawerContent(
                    viewModel = viewModel,
                    accounts = accounts,
                    selectedAccountEmail = selectedAccountEmail,
                    folders = folders,
                    selectedFolderName = selectedFolderName,
                    outbox = outbox,
                    backStack = backStack,
                    context = context,
                    scope = scope,
                    drawerState = drawerState,
                )
            }
        },
    ) {
        EmailNavGraph(viewModel, backStack, scope, drawerState)
    }
}

@Composable
private fun EmailDrawerContent(
    viewModel: EmailViewModel,
    accounts: List<EmailAccount>,
    selectedAccountEmail: String?,
    folders: List<com.vayunmathur.email.data.EmailFolder>,
    selectedFolderName: String,
    outbox: List<OutboxEntry>,
    backStack: NavBackStack<Route>,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    drawerState: DrawerState,
) {
    Column(Modifier.fillMaxHeight()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            DrawerInboxHeader(
                viewModel = viewModel,
                selectedAccountEmail = selectedAccountEmail,
                backStack = backStack,
                scope = scope,
                drawerState = drawerState,
            )
            DrawerAccountList(
                viewModel = viewModel,
                accounts = accounts,
                selectedAccountEmail = selectedAccountEmail,
                backStack = backStack,
                scope = scope,
                drawerState = drawerState,
            )
            DrawerFolderSection(
                viewModel = viewModel,
                selectedAccountEmail = selectedAccountEmail,
                folders = folders,
                selectedFolderName = selectedFolderName,
                backStack = backStack,
                scope = scope,
                drawerState = drawerState,
            )
            DrawerSystemItems(
                outbox = outbox,
                backStack = backStack,
                scope = scope,
                drawerState = drawerState,
            )
        }
        if (selectedAccountEmail != null) {
            HorizontalDivider()
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.logout_current_account)) },
                selected = false,
                onClick = { viewModel.logout(context); scope.launch { drawerState.close() } },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
            )
        }
    }
}

@Composable
private fun DrawerInboxHeader(
    viewModel: EmailViewModel,
    selectedAccountEmail: String?,
    backStack: NavBackStack<Route>,
    scope: kotlinx.coroutines.CoroutineScope,
    drawerState: DrawerState,
) {
    Text(
        stringResource(R.string.unified_inbox),
        modifier = Modifier.padding(16.dp),
        style = MaterialTheme.typography.titleMedium,
    )
    NavigationDrawerItem(
        label = { Text(stringResource(R.string.all_accounts)) },
        selected = selectedAccountEmail == null,
        onClick = {
            viewModel.selectAccount("")
            backStack.reset(Route.MessageList)
            scope.launch { drawerState.close() }
        },
        icon = { IconInbox() },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
    )
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(
        stringResource(R.string.accounts),
        modifier = Modifier.padding(16.dp),
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun DrawerAccountList(
    viewModel: EmailViewModel,
    accounts: List<EmailAccount>,
    selectedAccountEmail: String?,
    backStack: NavBackStack<Route>,
    scope: kotlinx.coroutines.CoroutineScope,
    drawerState: DrawerState,
) {
    accounts.forEach { account ->
        NavigationDrawerItem(
            label = { Text(account.email) },
            selected = account.email == selectedAccountEmail,
            onClick = {
                viewModel.selectAccount(account.email)
                backStack.reset(Route.MessageList)
                scope.launch { drawerState.close() }
            },
            icon = {
                val accountColor = Color(accountColor(account.email))
                Surface(
                    shape = CircleShape,
                    color = accountColor,
                    modifier = Modifier.size(24.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        com.vayunmathur.library.ui.IconMail(
                            modifier = Modifier.size(16.dp),
                            tint = contentColorOn(accountColor),
                        )
                    }
                }
            },
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
        )
    }
    NavigationDrawerItem(
        label = { Text(stringResource(R.string.add_account)) },
        selected = false,
        onClick = { backStack.add(Route.AddAccount); scope.launch { drawerState.close() } },
        icon = { IconAdd() },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
    )
}

@Composable
private fun DrawerFolderSection(
    viewModel: EmailViewModel,
    selectedAccountEmail: String?,
    folders: List<EmailFolder>,
    selectedFolderName: String,
    backStack: NavBackStack<Route>,
    scope: kotlinx.coroutines.CoroutineScope,
    drawerState: DrawerState,
) {
    if (selectedAccountEmail != null) {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            stringResource(R.string.folders),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        FolderList(folders, selectedFolderName) { folderName ->
            viewModel.selectFolder(folderName)
            backStack.reset(Route.MessageList)
            scope.launch { drawerState.close() }
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
}

@Composable
private fun DrawerSystemItems(
    outbox: List<OutboxEntry>,
    backStack: NavBackStack<Route>,
    scope: kotlinx.coroutines.CoroutineScope,
    drawerState: DrawerState,
) {
    NavigationDrawerItem(
        label = {
            Text(
                if (outbox.isEmpty()) {
                    stringResource(R.string.outbox)
                } else {
                    stringResource(R.string.outbox_with_count, outbox.size)
                },
            )
        },
        selected = false,
        onClick = { backStack.add(Route.Outbox); scope.launch { drawerState.close() } },
        icon = { IconSend() },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
    )
    NavigationDrawerItem(
        label = { Text(stringResource(UiR.string.settings)) },
        selected = false,
        onClick = { backStack.add(Route.Settings); scope.launch { drawerState.close() } },
        icon = { com.vayunmathur.library.ui.IconSettings() },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
    )
    NavigationDrawerItem(
        label = { Text(stringResource(R.string.drafts)) },
        selected = false,
        onClick = { backStack.add(Route.Drafts); scope.launch { drawerState.close() } },
        icon = { com.vayunmathur.library.ui.IconEdit() },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
    )
}

@Composable
private fun EmailNavGraph(
    viewModel: EmailViewModel,
    backStack: NavBackStack<Route>,
    scope: kotlinx.coroutines.CoroutineScope,
    drawerState: DrawerState,
) {
    MainNavigation(backStack) {
        messageListEntry(viewModel, backStack, scope, drawerState)
        messageThreadEntry(viewModel, backStack)
        // Morph: a draft's subject line travels out of the drafts list into the subject field.
        // The other five ways in carry no draft id, so their subject key is null and the screen
        // simply crossfades.
        entry<Route.Composer>(metadata = ListDetailPage() + MorphPage()) { route ->
            ComposerScreen(
                viewModel = viewModel,
                initialTo = route.to,
                initialSubject = route.subject,
                initialBody = route.body,
                inReplyTo = route.inReplyTo,
                references = route.references,
                draftId = route.draftId,
                onBack = { backStack.pop() },
            )
        }
        miscEntries(viewModel, backStack)
    }
}

private fun EntryProviderScope<Route>.messageListEntry(
    viewModel: EmailViewModel,
    backStack: NavBackStack<Route>,
    scope: kotlinx.coroutines.CoroutineScope,
    drawerState: DrawerState,
) {
    entry<Route.MessageList>(metadata = ListPage()) {
        MessageListPage(
            viewModel = viewModel,
            onMessageClick = { msg ->
                val thread = msg.threadId ?: msg.id.toString()
                backStack.add(Route.MessageThread(msg.accountEmail, thread))
            },
            onComposeClick = { backStack.add(Route.Composer()) },
            onOpenDrawer = { scope.launch { drawerState.open() } }
        )
    }
}

private fun EntryProviderScope<Route>.messageThreadEntry(
    viewModel: EmailViewModel,
    backStack: NavBackStack<Route>,
) {
    entry<Route.MessageThread>(metadata = ListDetailPage() + MorphPage()) { route ->
        MessageThreadPage(
            viewModel = viewModel,
            accountEmail = route.accountEmail,
            threadId = route.threadId,
            onBack = { backStack.pop() },
            onReply = { to, sub, ref ->
                val subject = "Re: $sub"
                backStack.add(
                    Route.Composer(to = to, subject = subject, references = ref, inReplyTo = ref),
                )
            },
            onForward = { sub, body ->
                val subject = "Fwd: $sub"
                val quote = "\n\n---------- Forwarded message ----------\n$body"
                backStack.add(Route.Composer(subject = subject, body = quote))
            },
            onCompose = { to, sub -> backStack.add(Route.Composer(to = to, subject = sub)) }
        )
    }
}

private fun EntryProviderScope<Route>.miscEntries(
    viewModel: EmailViewModel,
    backStack: NavBackStack<Route>,
) {
        entry<Route.Outbox>(metadata = ListDetailPage()) {
            OutboxScreen(viewModel = viewModel, onBack = { backStack.pop() })
        }
        entry<Route.AddAccount>(metadata = ListDetailPage()) {
            AddAccountScreen(onBack = { backStack.pop() }, onAccountAdded = { backStack.pop() })
        }
        entry<Route.Settings>(metadata = ListDetailPage()) {
            SettingsScreen(viewModel = viewModel, onBack = { backStack.pop() })
        }
        entry<Route.Drafts>(metadata = ListDetailPage()) {
            DraftsScreen(
                viewModel = viewModel,
                onBack = { backStack.pop() },
                onOpenDraft = { id -> backStack.add(Route.Composer(draftId = id)) },
            )
        }
        entry<Route.EmlViewer>(metadata = ListDetailPage()) { route ->
            EmlViewerScreen(
                uriString = route.uriString,
                onBack = { backStack.pop() },
                onComposeForward = { sub, body ->
                    backStack.add(Route.Composer(subject = sub, body = body))
                },
            )
        }
    }
