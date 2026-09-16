package com.vayunmathur.parentalcontrols.ui

import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.parentalcontrols.R
import com.vayunmathur.parentalcontrols.platform.WebContentFilters

/**
 * Web content filters, reached from the parental-controls dashboard. Parent-gated like every
 * other control. A single "filter explicit sites" preference; see [WebContentFilters] for the
 * note on enforcement.
 */
class WebContentFiltersActivity : PinGatedActivity() {

    override fun onPinVerifiedContent() {
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                WebContentFiltersScreen()
            }
        }
    }
}

/** The single-toggle web-filter editor. */
@Composable
fun WebContentFiltersScreen() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(WebContentFilters.isEnabled(context)) }

    LazyListScaffold(
        title = stringResource(R.string.web_filters_title),
        horizontalPadding = 0.dp,
        scrollBehavior = appBarScrollBehavior(),
    ) {
        item {
            SettingsSection {
                SettingsSwitchRow(
                    title = stringResource(R.string.web_filters_toggle),
                    supportingText = stringResource(R.string.web_filters_toggle_hint),
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        WebContentFilters.setEnabled(context, it)
                    },
                )
            }
        }
    }
}
