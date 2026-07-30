package com.vayunmathur.calculator.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.calculator.util.AngleMode
import com.vayunmathur.calculator.util.CalculatorViewModel
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ButtonDefaults
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CenterAlignedTopAppBar
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

private enum class KeyEmphasis { Digit, Operator, Primary, Function, Toggle }

/**
 * A keypad key. [second]/[secondPress] give an alternate label+action shown when the
 * "2nd" modifier is active (e.g. sin → sin⁻¹). [weight] lets a key span extra columns.
 */
private class Key(
    val label: String,
    val emphasis: KeyEmphasis = KeyEmphasis.Digit,
    val weight: Float = 1f,
    val second: String? = null,
    val onPress: (CalculatorViewModel) -> Unit,
    val secondPress: ((CalculatorViewModel) -> Unit)? = null,
)

@Composable
private fun RowScope.KeyButton(key: Key, viewModel: CalculatorViewModel, second: Boolean, onToggleSecond: () -> Unit) {
    val showSecond = second && key.second != null
    val label = if (showSecond) key.second!! else key.label
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
                showSecond -> key.secondPress?.invoke(viewModel)
                else -> key.onPress(viewModel)
            }
        },
        modifier = Modifier.weight(key.weight).height(50.dp).padding(2.dp),
        shape = RoundedCornerShape(16.dp),
        colors = colors,
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(label, fontSize = if (label.length >= 4) 13.sp else 18.sp, maxLines = 1)
    }
}

@Composable
fun CalculatorPage(viewModel: CalculatorViewModel) {
    var showHistory by remember { mutableStateOf(false) }
    var second by remember { mutableStateOf(false) }

    fun ins(text: String): (CalculatorViewModel) -> Unit = { it.append(text) }

    val rows: List<List<Key>> = listOf(
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
            Key(if (viewModel.angleMode == AngleMode.DEGREES) "DEG" else "RAD", KeyEmphasis.Function) { it.toggleAngleMode() },
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

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Calculator") },
                actions = { IconButton({ showHistory = true }) { IconHistory() } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // ---- Display ----
            Column(
                Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.End,
            ) {
                if (viewModel.memory != 0.0) {
                    Text("M", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                }
                Text(
                    viewModel.input.ifEmpty { "0" }
                        .replace("*", "×").replace("/", "÷").replace("-", "−"),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    textAlign = TextAlign.End,
                    fontSize = 38.sp,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    viewModel.preview,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    textAlign = TextAlign.End,
                    fontSize = 24.sp,
                    maxLines = 1,
                    softWrap = false,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            // ---- Keypad ----
            Column(Modifier.fillMaxWidth().padding(4.dp)) {
                rows.forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { key -> KeyButton(key, viewModel, second) { second = !second } }
                    }
                }
            }
        }
    }

    if (showHistory) HistoryDialog(viewModel) { showHistory = false }
}

@Composable
private fun HistoryDialog(viewModel: CalculatorViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("History") },
        text = {
            if (viewModel.history.isEmpty()) {
                Text("No calculations yet.")
            } else {
                LazyColumn(Modifier.height(320.dp)) {
                    items(viewModel.history) { entry ->
                        Card(
                            onClick = { viewModel.useHistory(entry); onDismiss() },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    entry.expression,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 14.sp,
                                )
                                Text("= ${entry.result}", fontSize = 20.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
        dismissButton = {
            if (viewModel.history.isNotEmpty()) {
                TextButton({ viewModel.clearHistory(); onDismiss() }) { Text("Clear") }
            }
        },
    )
}
