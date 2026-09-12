package com.vayunmathur.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconArrowForward
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconShield
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.TopAppBarDefaults
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.web.R
import androidx.compose.ui.text.style.TextOverflow
import com.vayunmathur.web.platform.BrowserUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserChrome(
    omniboxText: String,
    tabCount: Int,
    canGoBack: Boolean = false,
    canGoForward: Boolean = false,
    progress: Float = 0f,
    atBottom: Boolean = false,
    onBack: () -> Unit = {},
    onForward: () -> Unit = {},
    onOmniboxClick: () -> Unit = {},
    onTabSwitcherClick: () -> Unit = {},
    shieldHost: String? = null,
    blockedCount: Int = 0,
    onShieldClick: () -> Unit = {},
    onMenuClick: () -> Unit = {},
    menu: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    // RAW SCAFFOLD EXCEPTION: bespoke browser toolbar chrome. The bar is a
    // Column of a custom TopAppBar (back/forward nav row, a tappable read-only
    // address pill as the title, and shield + tab-count + overflow-menu actions
    // on a surface-colored bar) with a page LinearProgressIndicator drawn
    // alongside it, and it can sit in either the top or the bottom slot. That
    // composite bar has no equivalent in the shared scaffolds, and the content
    // is the full-bleed WebView.
    val bar: @Composable () -> Unit = {
        // At the bottom, TopAppBar's hardcoded top inset would be a status bar's
        // height of dead space, and nothing else applies the navigation bar inset.
        // The bar's own container color stops at the padding, so the surface is
        // painted here instead to reach the screen edge. The IME inset is left alone
        // on purpose: MainNavigation already owns it, and navigationBarsPadding
        // resolves to zero once the keyboard has consumed more than a navigation
        // bar's height.
        val barModifier = if (atBottom) {
            Modifier
                .background(MaterialTheme.colorScheme.surface)
                .navigationBarsPadding()
                .consumeWindowInsets(WindowInsets.statusBars)
        } else {
            Modifier
        }
        Column(barModifier) {
            // The progress bar belongs against the web content, so it moves to the
            // far side of the toolbar when the toolbar moves to the bottom.
            if (atBottom) PageProgress(progress)
            TopAppBar(
                navigationIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack, enabled = canGoBack) { IconBack() }
                        IconButton(onClick = onForward, enabled = canGoForward) { IconArrowForward() }
                    }
                },
                title = {
                    DisplayOnlyAddressPill(
                        fullUrl = omniboxText,
                        onClick = onOmniboxClick,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                actions = {
                    if (shieldHost != null) {
                        ShieldChip(blockedCount = blockedCount, onClick = onShieldClick)
                        Spacer(Modifier.width(4.dp))
                    }
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(onClick = onTabSwitcherClick)
                    ) {
                        Text(
                            text = tabCount.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onMenuClick) { IconMoreVert() }
                    menu()
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
            if (!atBottom) PageProgress(progress)
        }
    }
    Scaffold(
        topBar = { if (!atBottom) bar() },
        bottomBar = { if (atBottom) bar() },
        content = content,
    )
}

/** The page load indicator, drawn only while a load is actually in flight. */
@Composable
private fun PageProgress(progress: Float) {
    if (progress in 0.01f..0.99f) {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp))
    }
}

/**
 * Toolbar shield. Shows the number of requests blocked on the current page, which is the
 * only feedback the user gets that shields are doing anything.
 */
@Composable
private fun ShieldChip(blockedCount: Int, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconShield(Modifier.size(18.dp))
            if (blockedCount > 0) {
                Spacer(Modifier.width(4.dp))
                Text(blockedCount.toString(), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun DisplayOnlyAddressPill(
    fullUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Now matches CommonSearchBar visually: OutlinedTextField 28dp rounded, search icon, same padding.
    Box(modifier = modifier) {
        OutlinedTextField(
            value = fullUrl,
            onValueChange = {},
            readOnly = true,
            placeholder = { Text(stringResource(R.string.search_or_enter_address)) },
            leadingIcon = { IconSearch() },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth()
        )
        // Overlay to handle tap without focusing the field
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(28.dp))
                .clickable(onClick = onClick)
        )
    }
}
