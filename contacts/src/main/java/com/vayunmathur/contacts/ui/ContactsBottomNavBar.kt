package com.vayunmathur.contacts.ui

import com.vayunmathur.library.ui.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.util.ContactsTab
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.util.NavBackStack

/** Bar for a screen that owns a back stack. */
@Composable
fun ContactsBottomNavBar(backStack: NavBackStack<Route>) {
    val currentRoute = backStack.last()
    ContactsBottomNavBar(
        selected = when (currentRoute) {
            is Route.GroupsList -> ContactsTab.Groups
            is Route.Settings -> ContactsTab.Settings
            else -> ContactsTab.Contacts
        },
        onSelect = { navigateToTab(backStack, it) },
    )
}

/** Bar for a stateless screen: which tab is current in, which tab was tapped out. */
@Composable
fun ContactsBottomNavBar(selected: ContactsTab, onSelect: (ContactsTab) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            icon = { IconPerson() },
            label = { Text(stringResource(R.string.contacts)) },
            selected = selected == ContactsTab.Contacts,
            onClick = { onSelect(ContactsTab.Contacts) }
        )
        NavigationBarItem(
            icon = { IconGroup() },
            label = { Text(stringResource(R.string.groups)) },
            selected = selected == ContactsTab.Groups,
            onClick = { onSelect(ContactsTab.Groups) }
        )
        NavigationBarItem(
            icon = { IconSettings() },
            label = { Text(stringResource(R.string.settings)) },
            selected = selected == ContactsTab.Settings,
            onClick = { onSelect(ContactsTab.Settings) }
        )
    }
}

/**
 * Moves [backStack] to [tab]. The list is the root, so the other two tabs replace each
 * other rather than stacking up.
 */
fun navigateToTab(backStack: NavBackStack<Route>, tab: ContactsTab) {
    val currentRoute = backStack.last()
    when (tab) {
        ContactsTab.Contacts -> if (currentRoute !is Route.ContactsList) backStack.pop()
        ContactsTab.Groups -> if (currentRoute !is Route.GroupsList) {
            if (currentRoute is Route.Settings) backStack.pop()
            backStack.add(Route.GroupsList())
        }
        ContactsTab.Settings -> if (currentRoute !is Route.Settings) {
            if (currentRoute is Route.GroupsList) backStack.pop()
            backStack.add(Route.Settings)
        }
    }
}
