package com.vayunmathur.contacts.ui

import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.util.ContactsTab
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.BottomNavBarItem
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
    // Tabs here are an enum rather than routes, which is why this uses the
    // content slot instead of the back-stack overload of BottomNavBar.
    BottomNavBar {
        BottomNavBarItem(
            selected = selected == ContactsTab.Contacts,
            onClick = { onSelect(ContactsTab.Contacts) },
            icon = { IconPerson() },
            label = stringResource(R.string.contacts),
        )
        BottomNavBarItem(
            selected = selected == ContactsTab.Groups,
            onClick = { onSelect(ContactsTab.Groups) },
            icon = { IconGroup() },
            label = stringResource(R.string.groups),
        )
        BottomNavBarItem(
            selected = selected == ContactsTab.Settings,
            onClick = { onSelect(ContactsTab.Settings) },
            icon = { IconSettings() },
            label = stringResource(UiR.string.settings),
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
