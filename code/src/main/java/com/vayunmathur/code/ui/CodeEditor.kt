package com.vayunmathur.code.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.code.R
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.code.syntax.SyntaxTransformation
import com.vayunmathur.code.syntax.rememberSyntaxColors
import com.vayunmathur.code.util.CodeActions
import com.vayunmathur.code.util.TabUiState
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconKeyboardArrowUp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/**
 * The editing surface: an optional find/replace bar above a scroll-synced line-number gutter
 * and a monospace [BasicTextField]. Syntax highlighting and match highlighting are applied
 * through a [SyntaxTransformation]; horizontal scrolling is enabled only when soft-wrap is off.
 */
@Composable
fun CodeEditor(
    tab: TabUiState,
    actions: CodeActions,
    softWrap: Boolean,
    showFind: Boolean,
    onCloseFind: () -> Unit,
    modifier: Modifier = Modifier,
    /** Preview seam: the query the find bar starts on. The app always starts it empty. */
    initialQuery: String = "",
) {
    // Find/replace state is per-tab, so it resets when switching files. Keyed on the file
    // name rather than the tab value: the latter is rebuilt on every keystroke, which would
    // clear the query as the user types.
    var query by remember(tab.name) { mutableStateOf(initialQuery) }
    var replacement by remember(tab.name) { mutableStateOf("") }
    var caseSensitive by remember(tab.name) { mutableStateOf(false) }
    var activeMatch by remember(tab.name) { mutableStateOf(0) }

    val text = tab.value.text
    val matches = remember(text, query, caseSensitive) {
        if (query.isEmpty()) {
            emptyList()
        } else {
            val found = ArrayList<IntRange>()
            var i = text.indexOf(query, 0, ignoreCase = !caseSensitive)
            while (i >= 0) {
                found.add(i until i + query.length)
                i = text.indexOf(query, i + query.length, ignoreCase = !caseSensitive)
            }
            found
        }
    }

    LaunchedEffect(matches.size) {
        if (activeMatch >= matches.size) activeMatch = 0
    }

    val goTo: (Int) -> Unit = { target ->
        if (matches.isNotEmpty()) {
            val idx = ((target % matches.size) + matches.size) % matches.size
            activeMatch = idx
            val range = matches[idx]
            actions.setSelection(TextRange(range.first, range.last + 1))
        }
    }

    Column(modifier.fillMaxSize()) {
        if (showFind) {
            FindBar(
                query = query,
                replacement = replacement,
                caseSensitive = caseSensitive,
                matchCount = matches.size,
                activeMatch = activeMatch,
                onQueryChange = { query = it; activeMatch = 0 },
                onReplacementChange = { replacement = it },
                onToggleCase = { caseSensitive = !caseSensitive },
                onNext = { goTo(activeMatch + 1) },
                onPrev = { goTo(activeMatch - 1) },
                onReplace = {
                    if (matches.isNotEmpty()) {
                        actions.replaceRange(matches[activeMatch], replacement)
                    }
                },
                onReplaceAll = { actions.replaceAll(matches, replacement) },
                onClose = onCloseFind,
            )
            HorizontalDivider()
        }

        val editorStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val syntaxColors = rememberSyntaxColors()
        val highlightMatches = if (showFind) matches else emptyList()
        val transformation = remember(tab.language, syntaxColors, highlightMatches, activeMatch) {
            SyntaxTransformation(tab.language.spec, syntaxColors, highlightMatches, activeMatch)
        }

        val verticalScroll = rememberScrollState()
        val horizontalScroll = rememberScrollState()
        val lineCount = remember(text) { text.count { it == '\n' } + 1 }

        Row(Modifier.fillMaxSize()) {
            LineGutter(lineCount, verticalScroll, editorStyle)
            BasicTextField(
                value = tab.value,
                onValueChange = actions::onEditorChange,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(verticalScroll)
                    .then(if (softWrap) Modifier else Modifier.horizontalScroll(horizontalScroll))
                    .padding(horizontal = 8.dp),
                textStyle = editorStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = transformation,
            )
        }
    }
}

/**
 * The line-number column. Rendered as a single right-aligned [Text] (one number per line) so
 * it stays cheap for large files, and sharing [verticalScroll] keeps it locked to the editor.
 * With soft-wrap on, wrapped lines shift the text below their number — an accepted trade-off.
 */
@Composable
private fun LineGutter(lineCount: Int, verticalScroll: androidx.compose.foundation.ScrollState, style: TextStyle) {
    val numbers = remember(lineCount) { (1..lineCount).joinToString("\n") }
    val width = (lineCount.toString().length * 10 + 16).dp
    Text(
        text = numbers,
        modifier = Modifier
            .verticalScroll(verticalScroll)
            .width(width)
            .padding(horizontal = 4.dp),
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Right,
    )
}

/** The find/replace bar: two fields, prev/next navigation, a match count and replace actions. */
@Composable
private fun FindBar(
    query: String,
    replacement: String,
    caseSensitive: Boolean,
    matchCount: Int,
    activeMatch: Int,
    onQueryChange: (String) -> Unit,
    onReplacementChange: (String) -> Unit,
    onToggleCase: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.find)) },
                singleLine = true,
            )
            val label = if (matchCount == 0) "0/0" else "${activeMatch + 1}/$matchCount"
            Text(label, modifier = Modifier.padding(horizontal = 8.dp))
            IconButton(onClick = onPrev, enabled = matchCount > 0) { IconKeyboardArrowUp() }
            IconButton(onClick = onNext, enabled = matchCount > 0) { IconChevronRight() }
            IconButton(onClick = onClose) { IconClose() }
        }
        Spacer(Modifier.size(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = replacement,
                onValueChange = onReplacementChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.replace)) },
                singleLine = true,
            )
            TextButton(onClick = onReplace, enabled = matchCount > 0) { Text(stringResource(R.string.replace)) }
            TextButton(onClick = onReplaceAll, enabled = matchCount > 0) { Text(stringResource(R.string.all)) }
        }
        Box {
            TextButton(onClick = onToggleCase) {
                Text(if (caseSensitive) "Case: On" else "Case: Off")
            }
        }
    }
}
