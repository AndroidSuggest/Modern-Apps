package com.vayunmathur.emergency.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.emergency.R
import com.vayunmathur.emergency.platform.EmergencyActions
import com.vayunmathur.emergency.platform.EmergencyUiState
import com.vayunmathur.emergency.platform.EmergencyViewModel
import com.vayunmathur.emergency.platform.HealthConnectMedical
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.FormSection
import com.vayunmathur.library.ui.LabeledTextField
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior

/**
 * Edits the owner's identity details and emergency contacts.
 *
 * Answers `android.settings.EDIT_EMERGENCY_INFO` like GrapheneOS's EditInfoActivity.
 * Contacts are picked with `ACTION_PICK` against `Phone.CONTENT_URI` so the stored value is
 * the per-number URI GrapheneOS persists (not the aggregate contact URI). Adding requires
 * `READ_CONTACTS`, requested here. Allergies and medications are never typed here - they are
 * read from Health Connect, which this screen only offers to connect.
 */
class EditInfoActivity : ComponentActivity() {

    private val viewModel: EmergencyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                EditInfoPage(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }
}

/** Binds the ViewModel to the stateless screen, owning the pick/permission launchers. */
@Composable
private fun EditInfoPage(viewModel: EmergencyViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pickContact = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        result.data?.data?.let { viewModel.addContact(it) }
    }
    val requestContacts = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.refresh()
    }
    // Health Connect has its own permission contract, distinct from the runtime one above.
    // Refusal is fine - the card just keeps showing whatever is already granted.
    val requestHealth = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { viewModel.refreshHealthConnect() }

    EditInfoScreen(
        state = state,
        actions = viewModel,
        onPickContact = {
            if (state.hasContactsAccess) {
                pickContact.launch(
                    Intent(
                        Intent.ACTION_PICK,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    ),
                )
            } else {
                requestContacts.launch(Manifest.permission.READ_CONTACTS)
            }
        },
        onConnectHealth = { requestHealth.launch(HealthConnectMedical.PERMISSIONS) },
    )
}

/** The editor: identity fields, a Health Connect connect prompt, and contacts add/remove. */
@Composable
fun EditInfoScreen(
    state: EmergencyUiState,
    actions: EmergencyActions,
    onPickContact: () -> Unit,
    onConnectHealth: () -> Unit = {},
) {
    // Local copies so typing does not wait for a repository round-trip; saved on each keystroke
    // (SharedPreferences writes are cheap) so leaving mid-edit loses nothing.
    var name by remember(state.info.name) { mutableStateOf(state.info.name) }
    var address by remember(state.info.address) { mutableStateOf(state.info.address) }
    var bloodType by remember(state.info.bloodType) { mutableStateOf(state.info.bloodType) }
    var organDonor by remember(state.info.organDonor) { mutableStateOf(state.info.organDonor) }
    val save = {
        actions.saveInfo(name, address, bloodType, organDonor)
    }

    LazyListScaffold(
        title = stringResource(R.string.app_name),
        horizontalPadding = 0.dp,
        scrollBehavior = appBarScrollBehavior(),
    ) {
        item {
            EditField(
                label = stringResource(R.string.field_name),
                value = name,
                onChange = { name = it; save() },
            )
        }
        item {
            EditField(
                label = stringResource(R.string.field_address),
                value = address,
                onChange = { address = it; save() },
            )
        }
        item {
            EditField(
                label = stringResource(R.string.field_blood_type),
                value = bloodType,
                onChange = { bloodType = it; save() },
            )
        }
        item {
            EditField(
                label = stringResource(R.string.field_organ_donor),
                value = organDonor,
                onChange = { organDonor = it; save() },
            )
        }

        if (state.health.available) {
            item {
                SettingsSection(title = stringResource(R.string.section_medical_info)) {
                    if (!state.health.granted) {
                        SettingsRow(
                            title = stringResource(R.string.health_connect_connect),
                            supportingText = stringResource(R.string.health_connect_hint),
                            onClick = onConnectHealth,
                        )
                    } else {
                        SettingsRow(
                            title = stringResource(R.string.health_connect_connected),
                            supportingText = stringResource(R.string.health_connect_hint),
                        )
                    }
                }
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.section_emergency_contacts)) {
                if (!state.hasContactsAccess) {
                    SettingsRow(
                        title = stringResource(R.string.contacts_permission_title),
                        supportingText = stringResource(R.string.contacts_permission_hint),
                        onClick = onPickContact,
                    )
                } else if (state.loading) {
                    SettingsRow(title = stringResource(R.string.contacts_loading))
                } else {
                    SettingsRow(
                        title = stringResource(R.string.add_emergency_contact),
                        onClick = onPickContact,
                    )
                }
                if (state.addFailed) {
                    SettingsRow(
                        title = stringResource(R.string.fail_add_contact),
                        onClick = actions::clearAddFailed,
                    )
                }
            }
        }

        for (contact in state.info.contacts) {
            item(key = contact.phoneUri.toString()) {
                SettingsRow(
                    title = contact.displayName.ifBlank { contact.phoneNumber },
                    supportingText = contactSubtitle(contact.phoneType, contact.phoneNumber),
                    trailingContent = {
                        TextButton(onClick = { actions.removeContact(contact.phoneUri) }) {
                            Text(stringResource(R.string.remove_contact))
                        }
                    },
                )
            }
        }
    }
}

/** One identity field: a titled section holding a single text field. */
@Composable
private fun EditField(label: String, value: String, onChange: (String) -> Unit) {
    FormSection(title = label) {
        LabeledTextField(
            value = value,
            onValueChange = onChange,
            label = label,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
    }
}

private fun contactSubtitle(type: String, number: String): String =
    if (type.isBlank()) number else "$type · $number"
