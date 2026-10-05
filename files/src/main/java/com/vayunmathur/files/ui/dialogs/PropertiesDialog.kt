package com.vayunmathur.files.ui.dialogs

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.files.R
import com.vayunmathur.files.platform.FileBrowserItem
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * File details (name, path, type, size, modification date, folder contents). Rendered when
 * the user picks Properties from a file's menu; dismissed with [onDismiss].
 */
@Composable
fun PropertiesDialog(item: FileBrowserItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val details = remember(item.key) { loadDetails(context, item) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(details.title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                PropertyRow(stringResource(R.string.prop_path), details.path)
                PropertyRow(stringResource(R.string.prop_type), details.typeLabel)
                details.sizeLabel?.let { size ->
                    PropertyRow(stringResource(R.string.prop_size), size)
                }
                PropertyRow(stringResource(R.string.prop_modified), details.modifiedLabel)
                details.contentsLabel?.let { contents ->
                    PropertyRow(stringResource(R.string.prop_items), contents)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

/** One label/value row inside the properties dialog. */
@Composable
private fun PropertyRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Everything [PropertiesDialog] draws, precomputed so the composable stays stateless. */
private data class FileDetails(
    val title: String,
    val path: String,
    val typeLabel: String,
    val sizeLabel: String?,
    val modifiedLabel: String,
    val contentsLabel: String?,
)

private fun loadDetails(context: Context, item: FileBrowserItem): FileDetails {
    val file = item.realFile
    if (file == null) {
        val typeLabel = if (item.isDirectory) {
            context.getString(R.string.type_folder)
        } else {
            context.getString(R.string.type_file)
        }
        return FileDetails(
            title = item.name,
            path = item.zipInnerPath ?: item.name,
            typeLabel = typeLabel,
            sizeLabel = item.size?.let { Formatter.formatShortFileSize(context, it) },
            modifiedLabel = formatDate(item.lastModified),
            contentsLabel = null,
        )
    }
    val typeLabel = if (file.isDirectory) {
        context.getString(R.string.type_folder)
    } else {
        context.getString(R.string.type_file)
    }
    return FileDetails(
        title = file.name,
        path = file.absolutePath,
        typeLabel = typeLabel,
        sizeLabel = if (file.isFile) Formatter.formatShortFileSize(context, file.length()) else null,
        modifiedLabel = formatDate(file.lastModified()),
        contentsLabel = if (file.isDirectory) countContents(file)?.toString() else null,
    )
}

private fun formatDate(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

private fun countContents(dir: File): Int? =
    runCatching { dir.listFiles()?.size }.getOrNull()
