package com.vayunmathur.calculator.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.calculator.R
import com.vayunmathur.calculator.util.AngleMode
import com.vayunmathur.calculator.util.CalculatorActions
import com.vayunmathur.calculator.util.CalculatorUiState
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ButtonDefaults
import com.vayunmathur.library.ui.ButtonGroup
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

internal enum class KeyEmphasis { Digit, Operator, Primary, Function, Toggle }

/** Which date/time picker (if any) is currently open. `DtDate`/`DtTime` are the two steps of
 * the combined date-and-time flow. */
internal enum class CalculatorPicker { None, Date, Time, DtDate, DtTime }

/**
 * A keypad key. [second]/[secondPress] give an alternate label+action shown when the
 * "2nd" modifier is active (e.g. sin → sin⁻¹). [weight] lets a key span extra columns.
 *
 * [onPress] is last so the common single-action key reads as a trailing lambda:
 * `Key("7") { it.append("7") }`.
 */
internal class Key(
    val label: String,
    val emphasis: KeyEmphasis = KeyEmphasis.Digit,
    val weight: Float = 1f,
    val second: String? = null,
    val secondPress: ((CalculatorActions) -> Unit)? = null,
    val onPress: (CalculatorActions) -> Unit,
)

@Composable
internal fun RowScope.KeyButton(key: Key, actions: CalculatorActions, second: Boolean, onToggleSecond: () -> Unit) {
    val showSecond = second && key.second != null
    val label = if (showSecond) key.second else key.label
    val secondActive = key.emphasis == KeyEmphasis.Toggle && second
    val colors = when (key.emphasis) {
        KeyEmphasis.Digit -> ButtonDefaults.filledTonalButtonColors()
        KeyEmphasis.Operator -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        KeyEmphasis.Primary -> ButtonDefaults.buttonColors()
        KeyEmphasis.Function -> ButtonDefaults.textButtonColors()
        KeyEmphasis.Toggle -> if (secondActive) ButtonDefaults.buttonColors() else ButtonDefaults.textButtonColors()
    }
    Button(
        onClick = {
            when {
                key.emphasis == KeyEmphasis.Toggle -> onToggleSecond()
                showSecond -> key.secondPress?.invoke(actions)
                else -> key.onPress(actions)
            }
        },
        modifier = Modifier.weight(key.weight).height(50.dp).padding(2.dp),
        shape = MaterialTheme.shapes.largeIncreased,
        colors = colors,
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(label, fontSize = if (label.length >= 4) 13.sp else 18.sp, maxLines = 1)
    }
}

/** Builds the 8-row keypad for the given angle mode. */
internal fun buildCalculatorRows(angleMode: AngleMode): List<List<Key>> {
    fun ins(text: String): (CalculatorActions) -> Unit = { it.append(text) }
    return listOf(
        listOf(
            Key("2nd", KeyEmphasis.Toggle) {},
            Key("π", KeyEmphasis.Function, second = "φ", onPress = ins("π"), secondPress = ins("phi")),
            Key("e", KeyEmphasis.Function, second = "τ", onPress = ins("e"), secondPress = ins("tau")),
            Key("!", KeyEmphasis.Function) { it.append("!") },
            Key("AC", KeyEmphasis.Operator) { it.clear() },
        ),
        listOf(
            Key("sin", KeyEmphasis.Function, second = "sin⁻¹", onPress = ins("sin("), secondPress = ins("asin(")),
            Key("cos", KeyEmphasis.Function, second = "cos⁻¹", onPress = ins("cos("), secondPress = ins("acos(")),
            Key("tan", KeyEmphasis.Function, second = "tan⁻¹", onPress = ins("tan("), secondPress = ins("atan(")),
            Key("ln", KeyEmphasis.Function, second = "eˣ", onPress = ins("ln("), secondPress = ins("e^(")),
            Key("log", KeyEmphasis.Function, second = "10ˣ", onPress = ins("log("), secondPress = ins("10^(")),
        ),
        listOf(
            Key("^", KeyEmphasis.Function, second = "x²", onPress = ins("^"), secondPress = ins("^2")),
            Key("√", KeyEmphasis.Function, second = "∛", onPress = ins("√("), secondPress = ins("cbrt(")),
            Key("(", KeyEmphasis.Function) { it.append("(") },
            Key(")", KeyEmphasis.Function) { it.append(")") },
            Key(",", KeyEmphasis.Function, second = "EE", onPress = ins(","), secondPress = ins("E")),
        ),
        listOf(
            Key("MC", KeyEmphasis.Function) { it.memoryClear() },
            Key("MR", KeyEmphasis.Function) { it.memoryRecall() },
            Key("M+", KeyEmphasis.Function) { it.memoryAdd() },
            Key("M-", KeyEmphasis.Function) { it.memorySubtract() },
            Key(if (angleMode == AngleMode.DEGREES) "DEG" else "RAD", KeyEmphasis.Function) { it.toggleAngleMode() },
        ),
        listOf(
            Key("7") { it.append("7") },
            Key("8") { it.append("8") },
            Key("9") { it.append("9") },
            Key("÷", KeyEmphasis.Operator) { it.append("/") },
            Key("⌫", KeyEmphasis.Operator) { it.backspace() },
        ),
        listOf(
            Key("4") { it.append("4") },
            Key("5") { it.append("5") },
            Key("6") { it.append("6") },
            Key("×", KeyEmphasis.Operator) { it.append("*") },
            Key("%", KeyEmphasis.Operator) { it.append("%") },
        ),
        listOf(
            Key("1") { it.append("1") },
            Key("2") { it.append("2") },
            Key("3") { it.append("3") },
            Key("−", KeyEmphasis.Operator) { it.append("-") },
            Key("nCr", KeyEmphasis.Function, second = "nPr", onPress = ins("nCr("), secondPress = ins("nPr(")),
        ),
        listOf(
            Key("ans", KeyEmphasis.Function) { it.append("ans") },
            Key("0") { it.append("0") },
            Key(".") { it.append(".") },
            Key("+", KeyEmphasis.Operator) { it.append("+") },
            Key("=", KeyEmphasis.Primary) { it.evaluate() },
        ),
    )
}

/** A keypad button that opens a picker (styled like a Function key, but driven by screen state
 * rather than a [CalculatorActions] call, so it can toggle a dialog). */
@Composable
internal fun RowScope.PickerKey(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.weight(1f).height(50.dp).padding(2.dp),
        shape = MaterialTheme.shapes.largeIncreased,
        colors = ButtonDefaults.textButtonColors(),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(label, fontSize = if (label.length >= 4) 13.sp else 18.sp, maxLines = 1)
    }
}

/** The readout column: extracted so the compact (stacked) and expanded (side-by-side)
 * layouts share one implementation. */
@Composable
internal fun DisplayColumn(state: CalculatorUiState, actions: CalculatorActions, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.End,
    ) {
        if (state.memory != 0.0) {
            Text("M", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
        }
        Text(
            com.vayunmathur.calculator.util.renderInputForDisplay(state.input).ifEmpty { "0" }
                .replace("*", "×").replace("/", "÷").replace("-", "−"),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            textAlign = TextAlign.End,
            fontSize = 38.sp,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            state.preview,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            textAlign = TextAlign.End,
            fontSize = 24.sp,
            maxLines = 1,
            softWrap = false,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.unitOptions.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.convert_to),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // A ButtonGroup rather than a scrolling row of chips: picking the output
                // unit is one choice, so the options should read as one control. The group
                // moves anything that does not fit into its overflow menu, which is easier
                // to find than a chip scrolled off the right edge.
                ButtonGroup(Modifier.weight(1f)) {
                    state.unitOptions.forEach { unit ->
                        toggleableItem(
                            checked = unit == state.selectedUnit,
                            label = unit.symbol,
                            onCheckedChange = { actions.selectOutputUnit(unit.token) },
                        )
                    }
                }
            }
        }
    }
}

/** The keypad column: extracted so the compact (stacked) and expanded (side-by-side)
 * layouts share one implementation. */
@Composable
internal fun KeypadColumn(
    rows: List<List<Key>>,
    actions: CalculatorActions,
    second: Boolean,
    onToggleSecond: () -> Unit,
    picker: CalculatorPicker,
    onPickerChange: (CalculatorPicker) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            PickerKey(stringResource(R.string.key_date)) { onPickerChange(CalculatorPicker.Date) }
            PickerKey(stringResource(R.string.key_time)) { onPickerChange(CalculatorPicker.Time) }
            PickerKey(stringResource(R.string.key_datetime)) { onPickerChange(CalculatorPicker.DtDate) }
        }
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { key -> KeyButton(key, actions, second) { onToggleSecond() } }
            }
        }
    }
}
