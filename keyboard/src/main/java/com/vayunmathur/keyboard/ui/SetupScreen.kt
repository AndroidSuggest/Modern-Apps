package com.vayunmathur.keyboard.ui

import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.vayunmathur.keyboard.util.KeyboardSettings
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CenterAlignedTopAppBar
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
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
    var keyHeight by remember { mutableFloatStateOf((ds.getDouble(keys.KEY_HEIGHT) ?: 1.0).toFloat()) }

    var testText by remember { mutableStateOf("") }

    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("Keyboard") }) }) { padding ->
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
            ) { Text("Enable keyboard") }

            OutlinedButton(
                onClick = { imm?.showInputMethodPicker() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Choose keyboard") }

            HorizontalDivider()

            Text("Settings", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

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
            SettingSwitch("Show suggestions", showSuggestions) {
                showSuggestions = it; scope.launch { ds.setBoolean(keys.SHOW_SUGGESTIONS, it) }
            }
            SettingSwitch("Auto-correct", autoCorrect) {
                autoCorrect = it; scope.launch { ds.setBoolean(keys.AUTO_CORRECT, it) }
            }
            SettingSwitch("Number row", numberRow) {
                numberRow = it; scope.launch { ds.setBoolean(keys.NUMBER_ROW, it) }
            }

            Column {
                Text("Key height", style = MaterialTheme.typography.bodyLarge)
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
                headlineContent = { Text("Theme") },
                supportingContent = { Text("Follows the system light/dark theme and Material You colors") },
            )

            HorizontalDivider()

            OutlinedTextField(
                value = testText,
                onValueChange = { testText = it },
                label = { Text("Try the keyboard here") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
            )
        }
    }
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
private fun SettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}
