package com.vayunmathur.office

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.ExpandVisibility
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.TextFieldDefaults
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.office.util.OfficeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Timer, search/replace, font-size and word-count bars for [DocumentScreen].
 *
 * Moved verbatim (split for file length); the screen's locals became [DocumentScreenState]
 * reads/writes, behavior identical.
 */
@Composable
fun DocumentFindBars(
    s: DocumentScreenState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    isTextDoc: Boolean,
    isPresentation: Boolean,
    wordCount: Int,
    charCount: Int,
    readingTime: Int,
    scope: CoroutineScope,
    listState: LazyListState,
) {
    ExpandVisibility(visible = s.showTimer && isPresentation) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("⏱ %02d:%02d".format(s.timerSeconds / 60, s.timerSeconds % 60), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { s.timerSeconds = 0 }) { Text(stringResource(R.string.reset)) }
                TextButton(onClick = { s.showTimer = false }) { Text(stringResource(R.string.stop)) }
            }
        }
    }
    ExpandVisibility(visible = s.showSearch) {
        Column {
            TextField(value = s.searchQuery, onValueChange = { s.searchQuery = it }, placeholder = { Text(stringResource(R.string.search_hint)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant),
                trailingIcon = {
                    Row {
                        if (isTextDoc) TextButton(onClick = { s.showReplaceBar = !s.showReplaceBar }) { Text(if (s.showReplaceBar) stringResource(R.string.hide) else stringResource(R.string.replace_1)) }
                        IconButton(onClick = { s.showSearch = false; s.searchQuery = ""; s.showReplaceBar = false }) { IconClose() }
                    }
                })
            if (isTextDoc && s.searchQuery.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    fun jump(delta: Int) {
                        val matches = viewModel.findMatchBlocks(s.searchQuery, s.matchCase, s.wholeWord)
                        s.findMatches = matches
                        if (matches.isEmpty()) return
                        s.findIndex = ((s.findIndex + delta) % matches.size + matches.size) % matches.size
                        scope.launch { listState.animateScrollToItem(matches[s.findIndex].coerceIn(0, document.content.size - 1)) }
                    }
                    val total = remember(s.searchQuery, s.matchCase, s.wholeWord, document) { viewModel.findMatchBlocks(s.searchQuery, s.matchCase, s.wholeWord).size }
                    TextButton(onClick = { jump(-1) }) { Text(stringResource(R.string.prev)) }
                    TextButton(onClick = { jump(1) }) { Text(stringResource(R.string.next)) }
                    Text(if (total > 0) "${(s.findIndex % total) + 1}/$total" else "0/0", style = MaterialTheme.typography.labelMedium)
                }
            }
            ExpandVisibility(visible = s.showReplaceBar && s.searchQuery.isNotEmpty()) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextField(value = s.replaceText, onValueChange = { s.replaceText = it }, placeholder = { Text(stringResource(R.string.replace_hint)) }, singleLine = true, modifier = Modifier.weight(1f),
                            colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant))
                        TextButton(onClick = { viewModel.replaceInDocument(s.searchQuery, s.replaceText, false, s.matchCase, s.wholeWord) }) { Text(stringResource(R.string.one)) }
                        TextButton(onClick = { val n = viewModel.replaceInDocument(s.searchQuery, s.replaceText, true, s.matchCase, s.wholeWord); if (n > 0) s.searchQuery = "" }) { Text(stringResource(R.string.all)) }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { s.matchCase = !s.matchCase }) { Text(if (s.matchCase) stringResource(R.string.case_on) else stringResource(R.string.case_1)) }
                        TextButton(onClick = { s.wholeWord = !s.wholeWord }) { Text(if (s.wholeWord) stringResource(R.string.word) else stringResource(R.string.word_1)) }
                    }
                }
            }
        }
    }
    ExpandVisibility(visible = s.showFontControl) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text("A", style = MaterialTheme.typography.bodySmall)
            Slider(value = s.fontSizeMultiplier, onValueChange = { s.fontSizeMultiplier = it }, valueRange = 0.5f..2.0f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            Text("A", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { s.fontSizeMultiplier = 1f }) { Text(stringResource(R.string.reset)) }
        }
    }
    ExpandVisibility(visible = s.showWordBar && isTextDoc) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(stringResource(R.string.words_characters_min_read, wordCount, charCount, readingTime),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        }
    }
}
