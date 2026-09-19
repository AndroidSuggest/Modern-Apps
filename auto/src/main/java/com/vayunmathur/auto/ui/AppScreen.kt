package com.vayunmathur.auto.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.auto.platform.CarLauncherState
import com.vayunmathur.auto.platform.HostTemplate
import com.vayunmathur.auto.platform.HostUiAction
import com.vayunmathur.auto.platform.HostUiGridItem
import com.vayunmathur.auto.platform.HostUiRow
import com.vayunmathur.auto.platform.HostUiSection
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/**
 * One hosted app's screen: renders its latest parsed template full-bleed.
 *
 * No title header: the app is identified by its highlighted dock icon (see
 * [LauncherBottomBar]), so the template keeps every pixel. Each template
 * kind gets its own renderer below; unhandled kinds already fell back to
 * [HostTemplate.Pane] text in the parsers, so the screen never blanks. The
 * navigation renderer keeps the `TextureView` interop island for the app's
 * own map `Surface` — Compose cannot host a third-party map surface
 * otherwise.
 */
@Composable
fun AppScreen(appId: String, modifier: Modifier = Modifier) {
    HostedTemplateBody(appId = appId, modifier = modifier.fillMaxSize())
}

@Composable
private fun HostedTemplateBody(appId: String, modifier: Modifier = Modifier) {
    val templates by CarLauncherState.templates.collectAsStateWithLifecycle()
    val template = templates[appId]
    when (template) {
        is HostTemplate.Navigation -> NavigationRenderer(template, modifier)
        is HostTemplate.PlaceList -> PlaceListRenderer(template, modifier)
        is HostTemplate.TemplateList -> ListRenderer(template, modifier)
        is HostTemplate.Grid -> GridRenderer(template, modifier)
        is HostTemplate.Pane -> PaneRenderer(template, modifier)
        is HostTemplate.Search -> SearchRenderer(template, modifier)
        is HostTemplate.Message -> MessageRenderer(template, modifier)
        is HostTemplate.Tabs -> TabsRenderer(template, modifier)
        is HostTemplate.MediaPlayback -> MediaPlaybackRenderer(template, modifier)
        is HostTemplate.SignIn -> SignInRenderer(template, modifier)
        is HostTemplate.InCall -> InCallRenderer(template, modifier)
        is HostTemplate.Keypad -> KeypadRenderer(template, modifier)
        is HostTemplate.MapWithContent -> MapWithContentRenderer(template, modifier)
        is HostTemplate.LegacyNav -> LegacyNavRenderer(template, modifier)
        null -> LoadingRenderer(modifier)
    }
}

@Composable
private fun NavigationRenderer(template: HostTemplate.Navigation, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val banner = listOfNotNull(template.cue, template.road).joinToString(" · ").takeIf { it.isNotBlank() }
        if (banner != null) {
            Text(text = banner, style = MaterialTheme.typography.headlineSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            template.distanceText?.let { Text(text = it, style = MaterialTheme.typography.bodyLarge) }
            template.etaText?.let { Text(text = it, style = MaterialTheme.typography.bodyLarge) }
        }
        template.lanesText?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
        MapSurfaceIsland(modifier = Modifier.fillMaxWidth().weight(1f))
        if (template.loading) CircularProgressIndicator()
        ActionRow(actions = template.actions)
    }
}

@Composable
private fun PlaceListRenderer(template: HostTemplate.PlaceList, modifier: Modifier = Modifier) {
    TemplateListBody(
        title = template.title,
        sections = listOf(HostUiSection(rows = template.rows)),
        loading = template.loading,
        actions = template.actions,
        modifier = modifier,
    )
}

@Composable
private fun ListRenderer(template: HostTemplate.TemplateList, modifier: Modifier = Modifier) {
    TemplateListBody(
        title = template.title,
        sections = template.sections,
        loading = template.loading,
        actions = template.actions,
        modifier = modifier,
    )
}

@Composable
private fun GridRenderer(template: HostTemplate.Grid, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        template.title?.let {
            item { Text(text = it, style = MaterialTheme.typography.headlineSmall) }
        }
        items(template.items, key = { it.title }) { item ->
            GridItemRow(item = item)
        }
        if (template.loading) {
            item { CircularProgressIndicator() }
        }
        if (template.actions.isNotEmpty()) {
            item { ActionRow(actions = template.actions) }
        }
    }
}

@Composable
private fun PaneRenderer(template: HostTemplate.Pane, modifier: Modifier = Modifier) {
    TemplateListBody(
        title = template.title,
        sections = listOf(HostUiSection(rows = template.rows)),
        loading = template.loading,
        actions = template.actions,
        modifier = modifier,
    )
}

@Composable
private fun SearchRenderer(template: HostTemplate.Search, modifier: Modifier = Modifier) {
    var text by remember(template.initialText) { mutableStateOf(template.initialText) }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                template.onSearchTextChanged?.invoke(it)
            },
            label = { Text(template.hint ?: "Search") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { template.onSearchSubmitted?.invoke(text) }),
            modifier = Modifier.fillMaxWidth(),
        )
        TemplateListBody(
            title = null,
            sections = listOf(HostUiSection(rows = template.rows)),
            loading = template.loading,
            actions = template.actions,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MessageRenderer(template: HostTemplate.Message, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                template.title?.let { Text(text = it, style = MaterialTheme.typography.headlineSmall) }
                template.message?.let { Text(text = it, style = MaterialTheme.typography.bodyLarge) }
                if (template.loading) CircularProgressIndicator()
            }
        }
        ActionRow(actions = template.actions)
    }
}

@Composable
private fun TabsRenderer(template: HostTemplate.Tabs, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            for (tab in template.tabs) {
                val selected = tab.contentId == template.activeContentId
                if (selected) {
                    Button(onClick = {}) { Text(tab.title ?: tab.contentId) }
                } else {
                    TextButton(onClick = { template.onTabSelected?.invoke(tab.contentId) }) {
                        Text(tab.title ?: tab.contentId)
                    }
                }
            }
        }
        val content = template.content
        if (content != null) {
            when (content) {
                is HostTemplate.TemplateList -> ListRenderer(content, Modifier.weight(1f))
                is HostTemplate.Grid -> GridRenderer(content, Modifier.weight(1f))
                is HostTemplate.Pane -> PaneRenderer(content, Modifier.weight(1f))
                is HostTemplate.Message -> MessageRenderer(content, Modifier.weight(1f))
                is HostTemplate.Search -> SearchRenderer(content, Modifier.weight(1f))
                is HostTemplate.Navigation -> NavigationRenderer(content, Modifier.weight(1f))
                else -> TemplateListBody(null, emptyList(), false, emptyList(), Modifier.weight(1f))
            }
        } else if (template.loading) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun MediaPlaybackRenderer(template: HostTemplate.MediaPlayback, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = template.title ?: "Now playing", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "Transport renders from the media session token.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun SignInRenderer(template: HostTemplate.SignIn, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                template.title?.let { Text(text = it, style = MaterialTheme.typography.headlineSmall) }
                template.message?.let { Text(text = it, style = MaterialTheme.typography.bodyLarge) }
                if (template.loading) CircularProgressIndicator()
            }
        }
        ActionRow(actions = template.actions)
    }
}

@Composable
private fun InCallRenderer(template: HostTemplate.InCall, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                template.title?.let { Text(text = it, style = MaterialTheme.typography.headlineSmall) }
                for (line in template.texts) {
                    Text(text = line, style = MaterialTheme.typography.bodyLarge)
                }
                if (template.loading) CircularProgressIndicator()
            }
        }
        ActionRow(actions = template.actions)
    }
}

@Composable
private fun KeypadRenderer(template: HostTemplate.Keypad, modifier: Modifier = Modifier) {
    var text by remember(template.phoneNumber) { mutableStateOf(template.phoneNumber) }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        template.title?.let { Text(text = it, style = MaterialTheme.typography.headlineSmall) }
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                template.onNumberChanged?.invoke(it)
            },
            label = { Text("Number") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        template.onPrimaryAction?.let { primary ->
            Button(onClick = primary) { Text(template.primaryTitle ?: "Call") }
        }
    }
}

@Composable
private fun MapWithContentRenderer(template: HostTemplate.MapWithContent, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MapSurfaceIsland(modifier = Modifier.fillMaxWidth().weight(1f))
        val content = template.content
        if (content != null) {
            when (content) {
                is HostTemplate.TemplateList -> ListRenderer(content, Modifier.weight(1f))
                is HostTemplate.Grid -> GridRenderer(content, Modifier.weight(1f))
                is HostTemplate.Pane -> PaneRenderer(content, Modifier.weight(1f))
                is HostTemplate.Message -> MessageRenderer(content, Modifier.weight(1f))
                is HostTemplate.Search -> SearchRenderer(content, Modifier.weight(1f))
                else -> Unit
            }
        }
        ActionRow(actions = template.actions)
    }
}

@Composable
private fun LegacyNavRenderer(template: HostTemplate.LegacyNav, modifier: Modifier = Modifier) {
    val state = template.state
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val banner = listOfNotNull(state.cue, state.road).joinToString(" · ").takeIf { it.isNotBlank() }
        banner?.let { Text(text = it, style = MaterialTheme.typography.headlineSmall) }
        state.distanceText?.let { Text(text = it, style = MaterialTheme.typography.bodyLarge) }
        state.lanesText?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
        state.etaText?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
        MapSurfaceIsland(modifier = Modifier.fillMaxWidth().weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (action in state.actions) {
                TextButton(onClick = action.onClick) { Text(action.title) }
            }
        }
    }
}

@Composable
private fun LoadingRenderer(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun TemplateListBody(
    title: String?,
    sections: List<HostUiSection>,
    loading: Boolean,
    actions: List<HostUiAction>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        title?.let {
            item { Text(text = it, style = MaterialTheme.typography.headlineSmall) }
        }
        for (section in sections) {
            section.header?.let { header ->
                item { Text(text = header, style = MaterialTheme.typography.titleMedium) }
            }
            items(section.rows, key = { it.title }) { row ->
                TemplateRow(row = row)
            }
        }
        if (loading) {
            item { CircularProgressIndicator() }
        }
        if (actions.isNotEmpty()) {
            item { ActionRow(actions = actions) }
        }
    }
}

@Composable
private fun TemplateRow(row: HostUiRow) {
    val click = row.onClick
    ListItem(
        headlineContent = { Text(row.title) },
        supportingContent = {
            if (row.texts.isNotEmpty()) Text(row.texts.joinToString("\n"))
        },
        trailingContent = {
            if (click != null) {
                TextButton(onClick = click) { Text(if (row.browse) "›" else "Open") }
            }
        },
    )
}

@Composable
private fun GridItemRow(item: HostUiGridItem) {
    val click = item.onClick
    ListItem(
        headlineContent = { Text(item.title) },
        supportingContent = {
            item.text?.let { Text(it) }
        },
        trailingContent = {
            if (item.loading) CircularProgressIndicator()
            else if (click != null) {
                TextButton(onClick = click) { Text("Open") }
            }
        },
    )
}

@Composable
private fun ActionRow(actions: List<HostUiAction>) {
    if (actions.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (action in actions) {
            Button(onClick = action.onClick) { Text(action.title) }
        }
    }
}

/**
 * The hosted map `Surface` as a `TextureView` interop island.
 *
 * The car-app framework hands the host a `SurfaceContainer` the app draws its
 * own map into; Compose cannot host that surface any other way. Lifecycle:
 * available forwards into the session mirror, destroy releases it — matching
 * the old `CarNavCardView` contract. Touches forward into the app's own
 * `SurfaceCallback` (pan/zoom/click in the app itself).
 */
@Composable
private fun MapSurfaceIsland(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        CarLauncherState.mapSurfaceListener?.invoke(Surface(surface), width, height)
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        CarLauncherState.mapSurfaceListener?.invoke(null, 0, 0)
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }
                setOnTouchListener { _, event ->
                    CarLauncherState.mapTouchForwarder?.invoke(event.action, event.x, event.y) == true
                }
            }
        },
        modifier = modifier,
    )
}
