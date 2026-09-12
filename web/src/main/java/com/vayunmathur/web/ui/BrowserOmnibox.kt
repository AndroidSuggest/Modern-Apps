package com.vayunmathur.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.web.R
import com.vayunmathur.web.platform.BrowserUtils
import com.vayunmathur.web.ui.components.SiteIcon

/**
 * Editing omnibox state: the suggestions under the focused field — the typed URL row,
 * matching bookmarks, and matching history. The field and scaffold shell stay in
 * [BrowserPage]; this is the shared suggestion list for both top and bottom layouts.
 */
@Composable
internal fun OmniboxSuggestions(
    currentDraft: String,
    filteredBookmarks: List<com.vayunmathur.web.data.Bookmark>,
    filteredHistory: List<com.vayunmathur.web.data.HistoryEntry>,
    searchEngine: com.vayunmathur.web.platform.SearchEngine,
    onNavigate: (String) -> Unit,
    paddingValues: PaddingValues,
    atBottom: Boolean,
) {
    DesktopMaxWidthContainer {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            // Anchored to the omnibox, so a short list stacks up from the field
            // instead of hanging off the far edge of the screen. Note this is not
            // reverseLayout: the section headers are separate items that have to
            // stay above the rows they label.
            verticalArrangement = if (atBottom) {
                Arrangement.spacedBy(2.dp, androidx.compose.ui.Alignment.Bottom)
            } else {
                Arrangement.spacedBy(2.dp)
            },
            contentPadding = if (atBottom) {
                PaddingValues(top = 24.dp, bottom = 8.dp)
            } else {
                PaddingValues(top = 8.dp, bottom = 24.dp)
            }
        ) {
            if (currentDraft.isNotBlank()) {
                item {
                    ListItem(
                        headlineContent = { Text(currentDraft, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                text = BrowserUtils.hostFromUrl(
                                    BrowserUtils.toNavigationUrl(currentDraft, searchEngine)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingContent = { IconSearch() },
                        modifier = Modifier.clickable { onNavigate(currentDraft) }
                    )
                }
                item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
            }

            if (filteredBookmarks.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.bookmarks),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
                items(filteredBookmarks, key = { "bm-${it.id}" }) { bm ->
                    ListItem(
                        headlineContent = {
                            Text(
                                bm.title.ifBlank { bm.url },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        supportingContent = {
                            Text(
                                BrowserUtils.prettyUrl(bm.url),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        leadingContent = { IconSearch() },
                        modifier = Modifier.clickable { onNavigate(bm.url) }
                    )
                }
            }

            if (filteredHistory.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.history),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).padding(top = if (filteredBookmarks.isNotEmpty()) 12.dp else 0.dp)
                    )
                }
                items(filteredHistory, key = { "h-${it.id}" }) { h ->
                    ListItem(
                        headlineContent = {
                            Text(
                                h.title.ifBlank { h.url },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        supportingContent = {
                            Text(
                                BrowserUtils.prettyUrl(h.url),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        leadingContent = { IconSearch() },
                        modifier = Modifier.clickable { onNavigate(h.url) }
                    )
                }
            }
        }
    }
}

@Composable
internal fun QuickAccess(
    bookmarks: List<com.vayunmathur.web.data.Bookmark>,
    history: List<com.vayunmathur.web.data.HistoryEntry>,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    faviconFor: (String) -> android.graphics.Bitmap? = { null },
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (bookmarks.isNotEmpty()) {
            item { Text(stringResource(R.string.bookmarks), style = MaterialTheme.typography.titleMedium) }
            item {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 16.dp)) {
                    items(bookmarks, key = { it.id }) { bm ->
                        Card(
                            modifier = Modifier.width(140.dp).clickable { onOpenUrl(bm.url) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                SiteIcon(
                                    icon = faviconFor(bm.url),
                                    label = bm.title.ifBlank { BrowserUtils.hostFromUrl(bm.url) },
                                    modifier = Modifier.size(28.dp),
                                    containerColor = MaterialTheme.colorScheme.surface,
                                )
                                Text(bm.title.ifBlank { BrowserUtils.hostFromUrl(bm.url) }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        if (history.isNotEmpty()) {
            item { Text(stringResource(R.string.recent), style = MaterialTheme.typography.titleMedium) }
            items(history, key = { it.id }) { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpenUrl(entry.url) }.padding(12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    SiteIcon(
                        icon = faviconFor(entry.url),
                        label = entry.title.ifBlank { BrowserUtils.hostFromUrl(entry.url) },
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.title.ifBlank { entry.url }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(BrowserUtils.prettyUrl(entry.url), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.blank_new_tab_tap_the_address_pill_to_se),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}
