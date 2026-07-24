package com.vayunmathur.messages.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconClose
import androidx.compose.ui.res.stringResource
import com.vayunmathur.messages.R

/** Max poll options most platforms accept (Telegram caps at 10). */
private const val MAX_OPTIONS = 10

/**
 * Compose a new poll: a question, 2–[MAX_OPTIONS] options, and a
 * multi-select toggle. [onCreate] fires with the trimmed question, the
 * non-blank options, and whether multiple answers are allowed.
 */
@Composable
fun PollDialog(
    onCreate: (question: String, options: List<String>, allowMultiple: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    var allowMultiple by remember { mutableStateOf(false) }

    val cleanOptions = options.map { it.trim() }.filter { it.isNotEmpty() }
    val canCreate = question.isNotBlank() && cleanOptions.size >= 2

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_poll)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    label = { Text(stringResource(R.string.question)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                options.forEachIndexed { index, value ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { options[index] = it },
                            label = { Text(stringResource(R.string.option, index + 1)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        // Keep at least two option fields present.
                        if (options.size > 2) {
                            IconButton(onClick = { options.removeAt(index) }) {
                                IconClose()
                            }
                        } else {
                            Spacer(Modifier.size(48.dp))
                        }
                    }
                }
                if (options.size < MAX_OPTIONS) {
                    TextButton(onClick = { options.add("") }) {
                        IconAdd()
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.add_option))
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.allow_multiple_answers),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Switch(checked = allowMultiple, onCheckedChange = { allowMultiple = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(question.trim(), cleanOptions, allowMultiple) },
                enabled = canCreate,
            ) { Text(stringResource(R.string.create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
