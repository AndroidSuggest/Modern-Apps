package com.vayunmathur.vpn.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.vpn.R
import com.vayunmathur.vpn.Route
import com.vayunmathur.vpn.util.VpnViewModel

@Composable
fun SettingsPage(backStack: NavBackStack<Route>, vm: VpnViewModel) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_about)) },
                navigationIcon = { IconNavigation(backStack) },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()),
        ) {
            ListItem(
                modifier = Modifier.fillMaxWidth().clickable { openVpnSettings(context) },
                content = { Text(stringResource(R.string.always_on_vpn)) },
            )
            ListItem(
                modifier = Modifier.fillMaxWidth().clickable { backStack.add(Route.BypassList) },
                content = { Text(stringResource(R.string.bypass_list)) },
            )
        }
    }
}
