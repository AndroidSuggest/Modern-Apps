package com.vayunmathur.library.ui.odf

import com.vayunmathur.library.ui.contentColorOn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.material3.Text

/**
 * Shared, framework-light text-run editor extracted from the Office app so the markdown editor and
 * Office can use a single implementation. Renders a contiguous run of paragraphs as one
 * [BasicTextField] with a visual transformation that injects list/heading prefixes and applies
 * inline span styling, and reports edits back through callbacks. Pure Compose + ODF model only.
 */

internal fun headingSizeSp(style: ParagraphStyle): Float? = when (style) {
    ParagraphStyle.HEADING1 -> 30f
    ParagraphStyle.HEADING2 -> 26f
    ParagraphStyle.HEADING3 -> 22f
    ParagraphStyle.HEADING4 -> 20f
    else -> null
}

/**
 * Remaps a caret offset when the editor's text is replaced externally (e.g. a collaborator's merged
 * edit) so the caret follows its surrounding content: insertions/deletions *before* the caret shift
 * it, changes *after* it leave it put, and an edit *at* the caret lands at the start of the change.
 */
fun remapCaret(old: String, new: String, caret: Int): Int {
    if (old == new) return caret
    val lim = minOf(old.length, new.length)
    var cp = 0
    while (cp < lim && old[cp] == new[cp]) cp++
    var cs = 0
    while (cs < (old.length - cp) && cs < (new.length - cp) && old[old.length - 1 - cs] == new[new.length - 1 - cs]) cs++
    val delta = new.length - old.length
    return when {
        caret <= cp -> caret                        // change is at/after the caret
        caret >= old.length - cs -> caret + delta   // change is entirely before the caret
        else -> cp                                  // caret sat inside the changed region
    }
}

/** A collaborator's caret to render in the editor: [offset] in the plain text, ARGB [color], [name]. */
data class RemoteCaret(val offset: Int, val color: Long, val name: String)

@Composable
fun ContinuousParagraphEditor(
    doc: OdfDocument.TextDocument,
    start: Int,
    endInclusive: Int,
    fontSizeMultiplier: Float,
    onSelectionChange: (Int, Int, Int, Int) -> Unit,
    onTextChange: (Int, Int, String) -> Unit,
    modifier: Modifier = Modifier,
    onEnter: (gPos: Int) -> Int? = { null },
    onBackspace: (gPos: Int) -> Int? = { null },
    onToggleCheckbox: ((globalParaIndex: Int) -> Unit)? = null,
    onFocusChangedCb: (Boolean) -> Unit = {},
    onDeletePrevBlock: () -> Unit = {},
    focusRequester: FocusRequester? = null,
    caretRequest: Int? = null,
    onCaretRequestHandled: () -> Unit = {},
    remoteCarets: List<RemoteCaret> = emptyList(),
) {
    val paras = (start..endInclusive).mapNotNull { (doc.content[it] as? OdfContentBlock.Paragraph)?.paragraph }
    val plainText = paras.joinToString("\n") { p -> p.spans.joinToString("") { it.text } }
    var tfv by remember { mutableStateOf(TextFieldValue(plainText)) }
    var pendingCaret by remember { mutableStateOf<Int?>(null) }
    if (tfv.text != plainText) {
        val caret = pendingCaret ?: remapCaret(tfv.text, plainText, tfv.selection.end)
        tfv = TextFieldValue(plainText, TextRange(caret.coerceIn(0, plainText.length)))
    }
    pendingCaret = null
    val onSurface = MaterialTheme.colorScheme.onSurface
    val prefixColor = MaterialTheme.colorScheme.onSurfaceVariant
    val lens = remember(paras) { paras.map { p -> p.spans.sumOf { it.text.length } } }
    val prefixes = remember(paras) { paras.map { listPrefixFor(it) } }
    // Recomputed every composition so list prefixes (e.g. renumbered ordered lists, checkbox state)
    // are always reflected immediately, even when the underlying text is unchanged.
    val transformation = VisualTransformation {
        buildDocTransformed(it.text, paras, lens, prefixes, onSurface, prefixColor, fontSizeMultiplier)
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // When the run's shape changes (a paragraph added/removed by Enter/Backspace), the caller's
    // tracked run range + selection go stale. Report the fresh range + caret so toolbar targeting
    // (focused paragraph) stays correct even before the next user interaction.
    var prevRange by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(start, endInclusive) {
        val key = start to endInclusive
        if (prevRange != null && prevRange != key) {
            onSelectionChange(start, endInclusive, tfv.selection.min, tfv.selection.max)
        }
        prevRange = key
    }
    // External request to focus this editor and move the caret (Int.MAX_VALUE = end).
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(caretRequest) {
        val req = caretRequest ?: return@LaunchedEffect
        val target = req.coerceIn(0, tfv.text.length)
        tfv = TextFieldValue(tfv.text, TextRange(target))
        onSelectionChange(start, endInclusive, target, target)
        focusRequester?.requestFocus()
        keyboard?.show()
        onCaretRequestHandled()
    }
    Box(modifier) {
        // Let Compose own the caret. The built-in cursor is positioned with the SAME internal
        // (visually-transformed) TextLayoutResult and OffsetMapping that lay out the aligned glyphs,
        // so getCursorRect reflects each paragraph's TextAlign (set via ParagraphStyle below) and the
        // caret stays aligned with the displayed text for left/center/right/justified paragraphs.
        BasicTextField(
            value = tfv,
            onValueChange = onValueChange@{ nv ->
                val oldText = tfv.text
                val oldSel = tfv.selection
                // Smart list behavior: detect a single newline insertion (Enter) or single-char
                // delete (Backspace) at a collapsed caret and offer it to the list handlers first.
                val isEnter = nv.selection.collapsed && nv.text.length == oldText.length + 1 &&
                    nv.selection.start in 1..nv.text.length && nv.text[nv.selection.start - 1] == '\n' &&
                    nv.text.substring(0, nv.selection.start - 1) + nv.text.substring(nv.selection.start) == oldText
                if (isEnter) {
                    val newCaret = onEnter(nv.selection.start - 1)
                    if (newCaret != null) { pendingCaret = newCaret; return@onValueChange }
                }
                val isBackspace = nv.selection.collapsed && oldSel.collapsed && nv.text.length == oldText.length - 1 &&
                    oldText.substring(0, nv.selection.start) + oldText.substring(nv.selection.start + 1) == nv.text
                if (isBackspace) {
                    val newCaret = onBackspace(nv.selection.start + 1)
                    if (newCaret != null) { pendingCaret = newCaret; return@onValueChange }
                }
                val textChanged = nv.text != oldText
                tfv = nv
                onSelectionChange(start, endInclusive, nv.selection.min, nv.selection.max)
                if (textChanged) onTextChange(start, endInclusive, nv.text)
            },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = onSurface, lineHeight = (22f * fontSizeMultiplier).sp),
            visualTransformation = transformation,
            onTextLayout = { layout = it },
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onPreviewKeyEvent { ev ->
                    // Backspace at the very start of this run deletes the object (image, page break,
                    // table of contents, chart…) sitting just above it.
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.Backspace &&
                        tfv.selection.collapsed && tfv.selection.start == 0 && start > 0
                    ) { onDeletePrevBlock(); true } else false
                }
                .onFocusChanged { onFocusChangedCb(it.isFocused) }
        )
        // Tappable overlays over each checkbox glyph so tapping toggles its checked state.
        val lay = layout
        if (onToggleCheckbox != null && lay != null) {
            val transStarts = runTransStarts(lens, prefixes)
            val density = LocalDensity.current
            paras.forEachIndexed { pi, para ->
                if (para.style == ParagraphStyle.LIST_ITEM && para.listType == ListType.CHECKBOX) {
                    val glyphOffset = transStarts[pi]
                    val rect = runCatching { lay.getBoundingBox(glyphOffset) }.getOrNull() ?: return@forEachIndexed
                    with(density) {
                        Box(
                            Modifier
                                .offset(x = rect.left.toDp(), y = rect.top.toDp())
                                .size(width = (rect.height).toDp(), height = rect.height.toDp())
                                .clickable { onToggleCheckbox(start + pi) }
                        )
                    }
                }
            }
        }
        // Remote collaborators' carets (live presence). Positions use the plain-text offset; for
        // plain prose the visual transform is identity so this is exact (list prefixes shift it a bit).
        if (lay != null && remoteCarets.isNotEmpty()) {
            val density = LocalDensity.current
            for (rc in remoteCarets) {
                val off = rc.offset.coerceIn(0, tfv.text.length)
                val rect = runCatching { lay.getCursorRect(off) }.getOrNull() ?: continue
                val color = Color(rc.color.toInt())
                with(density) {
                    Box(
                        Modifier
                            .offset(x = rect.left.toDp(), y = rect.top.toDp())
                            .width(2.dp)
                            .height((rect.bottom - rect.top).toDp())
                            .background(color)
                    )
                    Box(
                        Modifier
                            .offset(x = rect.left.toDp(), y = (rect.top - 14f).toDp())
                            .background(color)
                    ) {
                        Text(rc.name, color = contentColorOn(color), fontSize = 8.sp, modifier = Modifier.padding(horizontal = 2.dp))
                    }
                }
            }
        }
    }
}
