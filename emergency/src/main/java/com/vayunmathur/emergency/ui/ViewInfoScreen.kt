package com.vayunmathur.emergency.ui

import android.content.Intent
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.emergency.R
import com.vayunmathur.emergency.data.AllergyCriticality
import com.vayunmathur.emergency.data.EmergencyInfo
import com.vayunmathur.emergency.data.EmergencyKeys
import com.vayunmathur.emergency.platform.EmergencyUiState
import com.vayunmathur.emergency.platform.EmergencyViewModel
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior

/**
 * Read-only emergency info, reachable over the lock screen.
 *
 * Answers `android.telephony.action.EMERGENCY_ASSISTANCE` like GrapheneOS's
 * ViewInfoActivity: the owner's medical details and emergency contacts for a first
 * responder, with an edit entry hidden until setup is complete (same gate as the
 * original's options-menu check on `USER_SETUP_COMPLETE`).
 */
class ViewInfoActivity : ComponentActivity() {

    private val viewModel: EmergencyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                ViewInfoScreen(
                    state = state,
                    ownerName = ownerName(),
                    showEdit = isSetupComplete(),
                    onEdit = { startActivity(Intent(this, EditInfoActivity::class.java)) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun ownerName(): String =
        runCatching { getSystemService(UserManager::class.java)?.userName.orEmpty() }
            .getOrDefault("")

    private fun isSetupComplete(): Boolean =
        runCatching {
            Settings.Secure.getInt(contentResolver, "user_setup_complete", 0) == 1
        }.getOrDefault(true)
}

/** The read-only info + contacts view. Stateless; the activity wires the ViewModel. */
@Composable
fun ViewInfoScreen(
    state: EmergencyUiState,
    ownerName: String,
    showEdit: Boolean,
    onEdit: () -> Unit,
) {
    LazyListScaffold(
        title = stringResource(R.string.app_name),
        horizontalPadding = 0.dp,
        scrollBehavior = appBarScrollBehavior(),
    ) {
        if (ownerName.isNotBlank()) {
            item {
                SettingsSection {
                    SettingsRow(title = ownerName)
                }
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.tab_title_info)) {
                if (state.loading) {
                    SettingsRow(title = stringResource(R.string.contacts_loading))
                } else {
                    val rows = infoRows(state.info)
                    if (rows.isEmpty()) {
                        SettingsRow(title = stringResource(R.string.no_info))
                    } else {
                        // Value front and centre (title), label small underneath (supporting).
                        for ((label, value) in rows) {
                            SettingsRow(title = value, supportingText = label)
                        }
                    }
                }
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.tab_title_contacts)) {
                if (!state.loading && state.info.contacts.isEmpty()) {
                    SettingsRow(title = stringResource(R.string.no_info))
                }
            }
        }

        for (contact in state.info.contacts) {
            item(key = contact.phoneUri.toString()) {
                val context = LocalContext.current
                SettingsRow(
                    title = contact.displayName.ifBlank { contact.phoneNumber },
                    supportingText = contactSubtitle(contact.phoneType, contact.phoneNumber),
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, contact.phoneUri))
                        }
                    },
                )
            }
        }

        if (state.health.allergies.isNotEmpty()) {
            item {
                SettingsSection(title = stringResource(R.string.section_allergies)) {
                    for (allergy in state.health.allergies) {
                        val severity = when (allergy.criticality) {
                            AllergyCriticality.High -> stringResource(R.string.allergy_high)
                            AllergyCriticality.Low -> stringResource(R.string.allergy_low)
                            AllergyCriticality.Unknown -> null
                        }
                        SettingsRow(
                            title = allergy.displayName,
                            supportingText = allergy.reaction,
                            trailingContent = severity?.let { { Text(it) } },
                        )
                    }
                    SettingsRow(title = stringResource(R.string.from_health_connect))
                }
            }
        }

        if (state.health.medications.isNotEmpty()) {
            item {
                SettingsSection(title = stringResource(R.string.section_current_medications)) {
                    for (medication in state.health.medications) {
                        SettingsRow(title = medication.displayName)
                    }
                    SettingsRow(title = stringResource(R.string.from_health_connect))
                }
            }
        }

        if (showEdit && !state.loading) {
            item {
                SettingsSection {
                    SettingsRow(
                        title = stringResource(R.string.edit_info),
                        onClick = onEdit,
                    )
                }
            }
        }
    }
}

/** Non-empty identity rows in view order, each as a label to its display value. */
@Composable
private fun infoRows(info: EmergencyInfo): List<Pair<String, String>> {
    val labels = mapOf(
        EmergencyKeys.NAME to stringResource(R.string.field_name),
        EmergencyKeys.ADDRESS to stringResource(R.string.field_address),
        EmergencyKeys.BLOOD_TYPE to stringResource(R.string.field_blood_type),
        EmergencyKeys.ORGAN_DONOR to stringResource(R.string.field_organ_donor),
    )
    val values = mapOf(
        EmergencyKeys.NAME to info.name,
        EmergencyKeys.ADDRESS to info.address,
        EmergencyKeys.BLOOD_TYPE to info.bloodType,
        EmergencyKeys.ORGAN_DONOR to info.organDonor,
    )
    // Name first, then the rest in view order.
    return (listOf(EmergencyKeys.NAME) + EmergencyKeys.VIEW_ORDER).mapNotNull { key ->
        val value = values[key].orEmpty()
        if (value.isBlank()) null else (labels[key].orEmpty() to value)
    }
}

private fun contactSubtitle(type: String, number: String): String =
    if (type.isBlank()) number else "$type · $number"
