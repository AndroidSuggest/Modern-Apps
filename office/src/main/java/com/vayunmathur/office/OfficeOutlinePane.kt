package com.vayunmathur.office

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.DrawerState
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.odf.OdfBookmark
import com.vayunmathur.office.ui.HeadingItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Outline pane shared by the drawer (compact) and the side panel (Expanded).
 *
 * Hoisted verbatim from `DocumentScreen`'s local `OutlinePane` (split for file length);
 * captures became explicit parameters, behavior identical.
 */
@Composable
fun OfficeOutlinePane(
    bookmarks: List<OdfBookmark>,
    headings: List<HeadingItem>,
    listState: LazyListState,
    drawerState: DrawerState,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(stringResource(R.string.outline), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
        HorizontalDivider()
        if (bookmarks.isNotEmpty()) {
            Text(stringResource(R.string.bookmarks), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp, 8.dp))
            LazyColumn(modifier = Modifier.height(150.dp)) {
                items(bookmarks) { bk ->
                    Text("🔖 ${bk.name}", modifier = Modifier.fillMaxWidth()
                        .clickable { scope.launch { listState.animateScrollToItem(bk.contentIndex); drawerState.close() } }
                        .padding(16.dp, 8.dp))
                }
            }
            HorizontalDivider()
        }
        LazyColumn {
            items(headings) { heading ->
                Text(heading.text,
                    style = when (heading.level) { 1 -> MaterialTheme.typography.titleMedium; 2 -> MaterialTheme.typography.titleSmall; else -> MaterialTheme.typography.bodyMedium },
                    fontWeight = if (heading.level <= 2) FontWeight.Bold else null,
                    modifier = Modifier.fillMaxWidth().clickable { scope.launch { listState.animateScrollToItem(heading.contentIndex); drawerState.close() } }
                        .padding(start = (16 + (heading.level - 1) * 16).dp, top = 12.dp, bottom = 12.dp, end = 16.dp),
                    maxLines = 2)
            }
        }
    }
}
