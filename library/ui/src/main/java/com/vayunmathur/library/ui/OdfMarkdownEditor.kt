package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import com.vayunmathur.library.ui.odf.ContinuousParagraphEditor
import com.vayunmathur.library.ui.odf.ListType
import com.vayunmathur.library.ui.odf.MarkdownOdfConverter
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.OdfTextEditorState
import com.vayunmathur.library.ui.odf.ParagraphStyle
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.R

/**
 * Controller for a Markdown body edited through the shared ODF editor. Holds the in-memory ODF
 * document (converted from Markdown), the current selection, and whether the field is focused.
 * Create one with [rememberOdfMarkdownEditorController], then place [OdfMarkdownEditorField] (the
 * editable text) and [OdfMarkdownEditorToolbar] (the formatting bar) wherever the layout needs them
 * — e.g. the field inline in a form and the toolbar in the Scaffold's bottom bar. For a simple
 * full-height editor, use the [OdfMarkdownEditor] convenience composable instead.
 */
class OdfMarkdownEditorController internal constructor(
    internal val editor: OdfTextEditorState,
) {
    internal var selStart by mutableIntStateOf(0)
    internal var selEnd by mutableIntStateOf(0)
    var focused by mutableStateOf(false)
        internal set
}

@Composable
fun rememberOdfMarkdownEditorController(
    initialMarkdown: String,
    onMarkdownChanged: (String) -> Unit,
): OdfMarkdownEditorController {
    val controller = remember {
        OdfMarkdownEditorController(OdfTextEditorState(MarkdownOdfConverter.markdownToOdf(initialMarkdown)))
    }
    val onChanged by rememberUpdatedState(onMarkdownChanged)
    // Report markdown on every edit after the initial value (drop(1) skips the StateFlow's current).
    LaunchedEffect(controller) {
        controller.editor.document.drop(1).collect { onChanged(MarkdownOdfConverter.odfToMarkdown(it)) }
    }
    return controller
}

/** The editable Markdown text field for [controller]. */
@Composable
fun OdfMarkdownEditorField(
    controller: OdfMarkdownEditorController,
    modifier: Modifier = Modifier,
    fontSizeMultiplier: Float = 1f,
) {
    val doc by controller.editor.document.collectAsState()
    val runEnd = doc.content.lastIndex.coerceAtLeast(0)
    ContinuousParagraphEditor(
        doc = doc,
        start = 0,
        endInclusive = runEnd,
        fontSizeMultiplier = fontSizeMultiplier,
        onSelectionChange = { _, _, s, e -> controller.selStart = s; controller.selEnd = e },
        onTextChange = { s, e, t -> controller.editor.updateParagraphRun(s, e, t) },
        onEnter = { gPos -> controller.editor.handleListEnter(0, runEnd, gPos) },
        onBackspace = { gPos -> controller.editor.handleListBackspace(0, runEnd, gPos) },
        onToggleCheckbox = { idx ->
            val para = (doc.content.getOrNull(idx) as? OdfContentBlock.Paragraph)?.paragraph
            if (para != null) controller.editor.setCheckboxChecked(idx, !para.listChecked)
        },
        onFocusChangedCb = { controller.focused = it },
        modifier = modifier,
    )
}

/**
 * The formatting toolbar for [controller], restricted to Markdown-representable actions.
 * [trailing] appends extra buttons (e.g. insert image / drawing) inside the same
 * scrollable row, so callers can combine formatting and insert actions in one bar.
 */
@Composable
fun OdfMarkdownEditorToolbar(
    controller: OdfMarkdownEditorController,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val doc by controller.editor.document.collectAsState()
    OdfMarkdownToolbar(controller.editor, doc, controller.selStart, controller.selEnd, trailing)
}

/**
 * Drop-in full-height Markdown body editor backed by the shared ODF editor: the editable field plus
 * its formatting toolbar. The stored format stays Markdown — [initialMarkdown] is converted to an
 * in-memory ODF document, edited natively (reliable soft-keyboard / list / checkbox handling), and
 * every change is converted back to Markdown via [onMarkdownChanged]. For form layouts where the
 * toolbar belongs elsewhere, use [rememberOdfMarkdownEditorController] with the field/toolbar
 * composables directly.
 */
@Composable
fun OdfMarkdownEditor(
    initialMarkdown: String,
    onMarkdownChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
    fontSizeMultiplier: Float = 1f,
    showToolbar: Boolean = true,
) {
    val controller = rememberOdfMarkdownEditorController(initialMarkdown, onMarkdownChanged)
    Column(modifier) {
        OdfMarkdownEditorField(
            controller = controller,
            modifier = Modifier
                .weight(1f, fill = true)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            fontSizeMultiplier = fontSizeMultiplier,
        )
        if (showToolbar) OdfMarkdownEditorToolbar(controller)
    }
}

/** Bottom formatting toolbar implementation shared by the field and convenience composables. */
@Composable
private fun OdfMarkdownToolbar(
    state: OdfTextEditorState,
    doc: OdfDocument.TextDocument,
    selStart: Int,
    selEnd: Int,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val runStart = 0
    val runEnd = doc.content.lastIndex.coerceAtLeast(0)
    val focusedPara = state.runParagraphIndexAt(runStart, runEnd, selStart)
    val para = (doc.content.getOrNull(focusedPara) as? OdfContentBlock.Paragraph)?.paragraph
    val isNumbered = para?.style == ParagraphStyle.LIST_ITEM && para.listType == ListType.NUMBERED
    val isCheckbox = para?.style == ParagraphStyle.LIST_ITEM && para.listType == ListType.CHECKBOX
    var headingMenu by remember { mutableStateOf(false) }

    val formatter = remember(doc, selStart, selEnd) {
        MarkdownRunFormatter(state, doc, runStart, runEnd, selStart, selEnd, focusedPara)
    }

    EditorBottomBar(scrollable = true) {
        EditorBaseButtons(formatter)

        FormatIconButton(
            Icons.Filled.Code, "Inline code",
            active = state.runRangeHasFormat(runStart, runEnd, selStart, selEnd) { it.fontFamily == "monospace" },
        ) {
            val active = state.runRangeHasFormat(runStart, runEnd, selStart, selEnd) { it.fontFamily == "monospace" }
            state.applyRunSpanStyle(runStart, runEnd, selStart, selEnd) {
                it.copy(fontFamily = if (active) null else "monospace")
            }
        }

        Box {
            FormatIconButton(Icons.Filled.Title, "Heading") { headingMenu = true }
            DropdownMenu(expanded = headingMenu, onDismissRequest = { headingMenu = false }) {
                fun setStyle(s: ParagraphStyle) {
                    headingMenu = false
                    state.mutateRunParagraphs(runStart, runEnd, selStart, selEnd) { it.copy(style = s) }
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.style_normal)) }, onClick = { setStyle(ParagraphStyle.BODY) })
                DropdownMenuItem(text = { Text(stringResource(R.string.style_heading1)) }, onClick = { setStyle(ParagraphStyle.HEADING1) })
                DropdownMenuItem(text = { Text(stringResource(R.string.style_heading2)) }, onClick = { setStyle(ParagraphStyle.HEADING2) })
                DropdownMenuItem(text = { Text(stringResource(R.string.style_heading3)) }, onClick = { setStyle(ParagraphStyle.HEADING3) })
                DropdownMenuItem(text = { Text(stringResource(R.string.style_heading4)) }, onClick = { setStyle(ParagraphStyle.HEADING4) })
            }
        }

        FormatIconButton(Icons.Filled.FormatListNumbered, "Numbered list", active = isNumbered) {
            if (focusedPara >= 0) state.toggleNumberedList(focusedPara)
        }
        FormatIconButton(Icons.Filled.CheckBox, "Checkbox", active = isCheckbox) {
            if (focusedPara >= 0) state.toggleCheckbox(focusedPara)
        }
        FormatIconButton(Icons.AutoMirrored.Filled.FormatIndentIncrease, "Increase nesting") {
            if (focusedPara >= 0) state.changeListLevel(focusedPara, 1)
        }
        FormatIconButton(Icons.AutoMirrored.Filled.FormatIndentDecrease, "Decrease nesting") {
            if (focusedPara >= 0) state.changeListLevel(focusedPara, -1)
        }

        trailing()
    }
}

/** [EditorFormatter] over an [OdfTextEditorState] run, restricted to markdown-representable formats. */
private class MarkdownRunFormatter(
    private val state: OdfTextEditorState,
    private val doc: OdfDocument.TextDocument,
    private val runStart: Int,
    private val runEnd: Int,
    private val selStart: Int,
    private val selEnd: Int,
    private val focusedPara: Int,
) : EditorFormatter {
    override val supported = setOf(
        EditorFormat.BOLD, EditorFormat.ITALIC, EditorFormat.STRIKETHROUGH,
        EditorFormat.BULLET, EditorFormat.LINK,
    )

    override fun isActive(format: EditorFormat): Boolean = when (format) {
        EditorFormat.BOLD -> has { it.bold }
        EditorFormat.ITALIC -> has { it.italic }
        EditorFormat.STRIKETHROUGH -> has { it.strikethrough }
        EditorFormat.BULLET -> {
            val p = (doc.content.getOrNull(focusedPara) as? OdfContentBlock.Paragraph)?.paragraph
            p?.style == ParagraphStyle.LIST_ITEM && p.listType == ListType.BULLET
        }
        else -> false
    }

    override fun toggle(format: EditorFormat) {
        when (format) {
            EditorFormat.BOLD -> { val t = !has { it.bold }; apply { it.copy(bold = t) } }
            EditorFormat.ITALIC -> { val t = !has { it.italic }; apply { it.copy(italic = t) } }
            EditorFormat.STRIKETHROUGH -> { val t = !has { it.strikethrough }; apply { it.copy(strikethrough = t) } }
            EditorFormat.BULLET -> if (focusedPara >= 0) state.toggleListItem(focusedPara)
            else -> {}
        }
    }

    override fun linkContext(): LinkContext? {
        val link = state.linkAt(runStart, runEnd, selStart)
        if (link != null) return LinkContext(editing = true, text = link.text, url = link.url)
        if (selStart != selEnd) return LinkContext(editing = false, text = selectedText(), url = "")
        return null
    }

    override fun applyLink(context: LinkContext, text: String, url: String) {
        if (url.isBlank()) return
        // Editing an existing link replaces its whole extent (no nested links); otherwise wrap the
        // current selection.
        val link = state.linkAt(runStart, runEnd, selStart)
        val gs: Int; val ge: Int
        if (link != null) { gs = link.gStart; ge = link.gEnd }
        else if (selStart != selEnd) { gs = selStart; ge = selEnd }
        else return
        state.setLink(runStart, runEnd, gs, ge, text.ifBlank { url }, url)
    }

    override fun removeLink(context: LinkContext) {
        val link = state.linkAt(runStart, runEnd, selStart) ?: return
        // Keep the text, drop the link styling.
        state.applyRunSpanStyle(runStart, runEnd, link.gStart, link.gEnd) {
            it.copy(href = null, underline = false, color = null)
        }
    }

    private fun has(predicate: (OdfSpan) -> Boolean) =
        state.runRangeHasFormat(runStart, runEnd, selStart, selEnd, predicate)

    private fun apply(transform: (OdfSpan) -> OdfSpan) =
        state.applyRunSpanStyle(runStart, runEnd, selStart, selEnd, transform)

    private fun selectedText(): String {
        val full = (runStart..runEnd).mapNotNull { (doc.content.getOrNull(it) as? OdfContentBlock.Paragraph)?.paragraph }
            .joinToString("\n") { p -> p.spans.joinToString("") { it.text } }
        val s = selStart.coerceIn(0, full.length)
        val e = selEnd.coerceIn(s, full.length)
        return full.substring(s, e)
    }
}
