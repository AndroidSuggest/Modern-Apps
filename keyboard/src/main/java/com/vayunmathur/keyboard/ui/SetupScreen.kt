package com.vayunmathur.keyboard.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.keyboard.R
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vayunmathur.keyboard.util.KeyboardLayouts
import com.vayunmathur.keyboard.util.KeyboardSettings
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CenterAlignedTopAppBar
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.RadioButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.launch

/**
 * Setup flow + live settings. Shows whether the IME is enabled/selected, offers the two
 * system shortcuts to do so, exposes every preference (persisted to DataStore, read live by
 * the service), and provides a field to try the keyboard immediately.
 */
@Composable
fun SetupScreen() {
    val context = LocalContext.current
    val ds = remember { DataStoreUtils.getInstance(context) }
    val scope = rememberCoroutineScope()
    val imm = remember { context.getSystemService(InputMethodManager::class.java) }

    fun isEnabled(): Boolean =
        imm?.enabledInputMethodList?.any { it.packageName == context.packageName } == true

    fun isSelected(): Boolean {
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return id != null && id.contains(context.packageName)
    }

    var enabled by remember { mutableStateOf(isEnabled()) }
    var selected by remember { mutableStateOf(isSelected()) }

    // Re-check status whenever we return from the system IME settings / picker.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = isEnabled()
                selected = isSelected()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val keys = KeyboardSettings.Keys
    var haptic by remember { mutableStateOf(ds.getBoolean(keys.HAPTIC, true)) }
    var sound by remember { mutableStateOf(ds.getBoolean(keys.SOUND, false)) }
    var autoCap by remember { mutableStateOf(ds.getBoolean(keys.AUTO_CAP, true)) }
    var doubleSpace by remember { mutableStateOf(ds.getBoolean(keys.DOUBLE_SPACE_PERIOD, true)) }
    var showSuggestions by remember { mutableStateOf(ds.getBoolean(keys.SHOW_SUGGESTIONS, true)) }
    var autoCorrect by remember { mutableStateOf(ds.getBoolean(keys.AUTO_CORRECT, false)) }
    var numberRow by remember { mutableStateOf(ds.getBoolean(keys.NUMBER_ROW, true)) }
    var clipboardEnabled by remember { mutableStateOf(ds.getBoolean(keys.CLIPBOARD, true)) }
    var keyHeight by remember { mutableFloatStateOf((ds.getDouble(keys.KEY_HEIGHT) ?: 1.0).toFloat()) }

    var layoutIds by remember { mutableStateOf(KeyboardSettings.decodeLayouts(ds.getString(keys.LAYOUTS))) }
    var activeLayoutId by remember {
        mutableStateOf(ds.getString(keys.ACTIVE_LAYOUT) ?: KeyboardLayouts.DEFAULT.id)
    }
    var showLayoutPicker by remember { mutableStateOf(false) }

    fun persistLayouts(ids: List<String>, active: String) {
        layoutIds = ids
        activeLayoutId = active
        scope.launch {
            ds.setString(keys.LAYOUTS, KeyboardSettings.encodeLayouts(ids))
            ds.setString(keys.ACTIVE_LAYOUT, active)
        }
    }

    /** Enable/disable a layout, keeping at least one enabled and the active id valid. */
    fun toggleLayout(id: String) {
        val next = if (id in layoutIds) layoutIds - id else layoutIds + id
        if (next.isEmpty()) return
        persistLayouts(next, if (activeLayoutId in next) activeLayoutId else next.first())
    }

    var testText by remember { mutableStateOf("") }

    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(enabled = enabled, selected = selected)

            Button(
                onClick = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.enable_keyboard)) }

            OutlinedButton(
                onClick = { imm?.showInputMethodPicker() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.choose_keyboard)) }

            HorizontalDivider()

            Text(
                stringResource(R.string.languages_and_layouts),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            val enabledLayouts = layoutIds.mapNotNull { KeyboardLayouts.byId(it) }
            enabledLayouts.forEach { layout ->
                SettingsRow(
                    title = layout.name,
                    supportingText = layout.description,
                    onClick = { persistLayouts(layoutIds, layout.id) },
                    leadingContent = {
                        RadioButton(
                            selected = layout.id == activeLayoutId,
                            onClick = { persistLayouts(layoutIds, layout.id) },
                        )
                    },
                    trailingContent = if (enabledLayouts.size > 1) {
                        { IconButton(onClick = { toggleLayout(layout.id) }) { IconClose() } }
                    } else {
                        null
                    },
                )
            }

            SettingsRow(
                title = stringResource(R.string.add_a_language_or_layout),
                supportingText = if (enabledLayouts.size > 1) {
                    stringResource(R.string.tap_the_globe_key_on_the_keyboard_to)
                } else {
                    null
                },
                onClick = { showLayoutPicker = true },
                leadingContent = { IconAdd() },
            )

            HorizontalDivider()

            Text(stringResource(UiR.string.settings), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

            SettingSwitch("Haptic feedback", haptic) {
                haptic = it; scope.launch { ds.setBoolean(keys.HAPTIC, it) }
            }
            SettingSwitch("Key sound", sound) {
                sound = it; scope.launch { ds.setBoolean(keys.SOUND, it) }
            }
            SettingSwitch("Auto-capitalize", autoCap) {
                autoCap = it; scope.launch { ds.setBoolean(keys.AUTO_CAP, it) }
            }
            SettingSwitch("Double-space inserts period", doubleSpace) {
                doubleSpace = it; scope.launch { ds.setBoolean(keys.DOUBLE_SPACE_PERIOD, it) }
            }
            // The word list we ship is English, so say so rather than let these two look
            // broken while a Greek or Thai layout is active.
            val englishOnly = KeyboardLayouts.byId(activeLayoutId)?.englishDictionary == false
            val englishNote =
                if (englishOnly) stringResource(R.string.available_for_english_layouts_only) else null
            SettingSwitch("Show suggestions", showSuggestions, englishNote) {
                showSuggestions = it; scope.launch { ds.setBoolean(keys.SHOW_SUGGESTIONS, it) }
            }
            SettingSwitch("Auto-correct", autoCorrect, englishNote) {
                autoCorrect = it; scope.launch { ds.setBoolean(keys.AUTO_CORRECT, it) }
            }
            SettingSwitch("Number row", numberRow) {
                numberRow = it; scope.launch { ds.setBoolean(keys.NUMBER_ROW, it) }
            }
            SettingSwitch(
                "Clipboard history",
                clipboardEnabled,
                "Remember what you copy and offer it back above the keys",
            ) {
                clipboardEnabled = it
                scope.launch { ds.setBoolean(keys.CLIPBOARD, it) }
            }
            if (clipboardEnabled) {
                // Blanking the stored value is the signal the running IME watches for; it
                // wipes its in-memory history (including the sensitive clips that never
                // reached disk) rather than letting this write be overwritten.
                SettingsRow(
                    title = stringResource(R.string.clear_clipboard_history),
                    onClick = { scope.launch { ds.setString(keys.CLIPS, "") } },
                    leadingContent = { IconDelete() },
                )
            }

            Column {
                Text(stringResource(R.string.key_height), style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = keyHeight,
                    onValueChange = { keyHeight = it },
                    valueRange = 0.8f..1.4f,
                    onValueChangeFinished = {
                        scope.launch { ds.setDouble(keys.KEY_HEIGHT, keyHeight.toDouble()) }
                    },
                )
            }

            ListItem(
                headlineContent = { Text(stringResource(R.string.theme)) },
                supportingContent = { Text(stringResource(R.string.follows_the_system_light_dark_theme_and)) },
            )

            HorizontalDivider()

            OutlinedTextField(
                value = testText,
                onValueChange = { testText = it },
                label = { Text(stringResource(R.string.try_the_keyboard_here)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
            )
        }
    }

    if (showLayoutPicker) {
        LayoutPickerDialog(
            enabled = layoutIds,
            onToggle = ::toggleLayout,
            onDismiss = { showLayoutPicker = false },
        )
    }
}

/**
 * The full catalogue, with the enabled layouts checked. Toggling applies immediately (there
 * is nothing to confirm), and the last enabled layout cannot be unchecked — a keyboard with
 * no letters would be unusable and unrecoverable from the keyboard itself.
 */
@Composable
private fun LayoutPickerDialog(
    enabled: List<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val matches = remember(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            KeyboardLayouts.ALL
        } else {
            KeyboardLayouts.ALL.filter {
                it.name.contains(q, ignoreCase = true) || it.description.contains(q, ignoreCase = true)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.languages_and_layouts)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.search_languages)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(matches, key = { it.id }) { layout ->
                        val checked = layout.id in enabled
                        SettingsRow(
                            title = layout.name,
                            supportingText = layout.description,
                            onClick = { onToggle(layout.id) },
                            // Unchecking the only remaining layout is a no-op, so the row
                            // reads as unavailable rather than silently doing nothing.
                            enabled = !checked || enabled.size > 1,
                            leadingContent = {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { onToggle(layout.id) },
                                    enabled = !checked || enabled.size > 1,
                                )
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.done)) } },
    )
}

@Composable
private fun StatusCard(enabled: Boolean, selected: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusRow("Enabled in system settings", enabled)
            StatusRow("Selected as active keyboard", selected)
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ok) {
            IconCheck(tint = MaterialTheme.colorScheme.primary)
        } else {
            IconClose(tint = MaterialTheme.colorScheme.error)
        }
        Text(label)
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    checked: Boolean,
    supportingText: String? = null,
    onChange: (Boolean) -> Unit,
) {
    SettingsSwitchRow(
        title = title,
        checked = checked,
        onCheckedChange = onChange,
        supportingText = supportingText,
    )
}
