package com.vayunmathur.code.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.code.syntax.LanguageSpec
import com.vayunmathur.code.syntax.SyntaxColors
import com.vayunmathur.code.syntax.rememberSyntaxColors
import com.vayunmathur.code.util.CodeActions
import com.vayunmathur.code.util.TabUiState
import com.vayunmathur.library.ui.MaterialTheme

/**
 * The experimental virtualized editor engine (Phase 7). A [Canvas] draws only the lines inside the
 * viewport, so syntax highlighting is computed per-visible-line instead of over the whole document.
 * It reuses the existing edit pipeline: input is routed through [CodeActions.onEditorChange] /
 * [CodeActions.setSelection], so undo/redo, auto-save and smart input all keep working.
 *
 * v1 scope (documented follow-ups): hardware-keyboard input and tap-to-place-caret are supported;
 * soft-keyboard IME and soft-wrap are not yet wired. Gated behind the `experimentalEditor` pref, so
 * the stable [CodeEditor] stays the default until this reaches parity.
 */
@Composable
fun CodeEditorView(
    tab: TabUiState,
    actions: CodeActions,
    fontSize: Int,
    editorTheme: String,
    modifier: Modifier = Modifier,
    onValueChangeOverride: ((TextFieldValue) -> Unit)? = null,
) {
    val value = tab.value
    val text = value.text
    val colors = rememberSyntaxColors(editorTheme)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant
    val caretColor = MaterialTheme.colorScheme.primary
    val spec = tab.language.spec

    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = remember(fontSize, onSurface) {
        TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, color = onSurface)
    }
    val metrics = remember(style) { measurer.measure("M", style) }
    val lineHeight = metrics.size.height.toFloat().coerceAtLeast(1f)
    val charWidth = metrics.size.width.toFloat().coerceAtLeast(1f)

    val lines = remember(text) { text.split("\n") }
    val lineStarts = remember(text) {
        IntArray(lines.size).also { starts ->
            var acc = 0
            for (i in lines.indices) {
                starts[i] = acc
                acc += lines[i].length + 1
            }
        }
    }
    val longestLine = remember(text) { lines.maxOfOrNull { it.length } ?: 0 }
    val gutterWidth = with(density) { ((lines.size.toString().length * 10) + 16).dp.toPx() }

    var scrollY by remember { mutableStateOf(0f) }
    var scrollX by remember { mutableStateOf(0f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val focusRequester = remember { FocusRequester() }

    val totalHeight = lineHeight * lines.size
    val totalWidth = gutterWidth + longestLine * charWidth + charWidth
    val maxScrollY = (totalHeight - viewport.height).coerceAtLeast(0f)
    val maxScrollX = (totalWidth - viewport.width).coerceAtLeast(0f)
    scrollY = scrollY.coerceIn(0f, maxScrollY)
    scrollX = scrollX.coerceIn(0f, maxScrollX)

    val vScroll = rememberScrollableState { delta ->
        val newY = (scrollY - delta).coerceIn(0f, maxScrollY)
        val consumed = scrollY - newY
        scrollY = newY
        consumed
    }
    val hScroll = rememberScrollableState { delta ->
        val newX = (scrollX - delta).coerceIn(0f, maxScrollX)
        val consumed = scrollX - newX
        scrollX = newX
        consumed
    }

    fun offsetAt(x: Float, y: Float): Int {
        val line = ((y + scrollY) / lineHeight).toInt().coerceIn(0, lines.size - 1)
        val col = (((x + scrollX) - gutterWidth) / charWidth).toInt().coerceIn(0, lines[line].length)
        return (lineStarts[line] + col).coerceIn(0, text.length)
    }

    val emit: (TextFieldValue) -> Unit = onValueChangeOverride ?: actions::onEditorChange

    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            .scrollable(vScroll, Orientation.Vertical)
            .scrollable(hScroll, Orientation.Horizontal)
            .pointerInput(text) {
                detectTapGestures { pos ->
                    val offset = offsetAt(pos.x, pos.y)
                    actions.setSelection(TextRange(offset))
                    focusRequester.requestFocus()
                }
            }
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                handleKey(event, value, emit) { range -> actions.setSelection(range) }
            },
    ) {
        viewport = IntSize(size.width.toInt(), size.height.toInt())
        val caret = if (value.selection.collapsed) value.selection.start else -1
        val selMin = value.selection.min
        val selMax = value.selection.max

        val first = (scrollY / lineHeight).toInt().coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val visible = (size.height / lineHeight).toInt() + 2
        val last = (first + visible).coerceAtMost(lines.size - 1)

        for (i in first..last) {
            val y = i * lineHeight - scrollY
            val lineStart = lineStarts[i]
            val lineLen = lines[i].length

            // Current-line background.
            if (caret in lineStart..(lineStart + lineLen)) {
                drawRect(colors.currentLine, topLeft = Offset(0f, y), size = Size(size.width, lineHeight))
            }

            // Selection background on this line.
            if (selMax > selMin) {
                val a = (selMin - lineStart).coerceIn(0, lineLen)
                val b = (selMax - lineStart).coerceIn(0, lineLen)
                if (b > a || (selMin <= lineStart + lineLen && selMax > lineStart + lineLen)) {
                    val endCol = if (selMax > lineStart + lineLen) lineLen else b
                    val startX = gutterWidth + a * charWidth - scrollX
                    val width = ((endCol - a).coerceAtLeast(0)) * charWidth + if (selMax > lineStart + lineLen) charWidth else 0f
                    if (width > 0f) drawRect(colors.match, topLeft = Offset(startX, y), size = Size(width, lineHeight))
                }
            }

            // Gutter line number.
            val number = measurer.measure(AnnotatedString((i + 1).toString()), style)
            drawText(number, color = gutterColor, topLeft = Offset(gutterWidth - number.size.width - 6f, y))

            // Line text with per-line syntax colors.
            val annotated = annotatedLine(lines[i], spec, colors)
            val layout = measurer.measure(annotated, style)
            drawText(layout, topLeft = Offset(gutterWidth - scrollX, y))

            // Caret.
            if (caret in lineStart..(lineStart + lineLen)) {
                val col = caret - lineStart
                val caretX = gutterWidth + col * charWidth - scrollX
                drawRect(caretColor, topLeft = Offset(caretX, y), size = Size(2f, lineHeight))
            }
        }
    }
}

/** Tokenizes a single line with [spec] and paints rainbow brackets; returns a colored string. */
private fun annotatedLine(line: String, spec: LanguageSpec?, colors: SyntaxColors): AnnotatedString {
    if (spec == null || line.isEmpty() || line.length > MAX_LINE_HIGHLIGHT) return AnnotatedString(line)
    val builder = AnnotatedString.Builder(line)
    if (colors.brackets.isNotEmpty()) {
        var depth = 0
        val n = colors.brackets.size
        for (i in line.indices) {
            when (line[i]) {
                '(', '[', '{' -> {
                    builder.addStyle(SpanStyle(color = colors.brackets[depth % n]), i, i + 1)
                    depth++
                }
                ')', ']', '}' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    builder.addStyle(SpanStyle(color = colors.brackets[depth % n]), i, i + 1)
                }
            }
        }
    }
    for (match in spec.regex.findAll(line)) {
        val kind = spec.kindFor(match) ?: continue
        builder.addStyle(SpanStyle(color = colors.colorFor(kind)), match.range.first, match.range.last + 1)
    }
    return builder.toAnnotatedString()
}

/**
 * Handles a hardware key press, producing the resulting [TextFieldValue] (via [emit]) or a caret
 * move (via [move]). Text inserts/deletes go through [emit] so the ViewModel's smart input,
 * undo/redo and auto-save all apply. Returns true when the event was consumed.
 */
private fun handleKey(
    event: androidx.compose.ui.input.key.KeyEvent,
    value: TextFieldValue,
    emit: (TextFieldValue) -> Unit,
    move: (TextRange) -> Unit,
): Boolean {
    val text = value.text
    val start = value.selection.min
    val end = value.selection.max
    when (event.key) {
        Key.Backspace -> {
            if (start != end) {
                emit(TextFieldValue(text.substring(0, start) + text.substring(end), TextRange(start)))
            } else if (start > 0) {
                emit(TextFieldValue(text.substring(0, start - 1) + text.substring(start), TextRange(start - 1)))
            }
            return true
        }
        Key.Delete -> {
            if (start != end) {
                emit(TextFieldValue(text.substring(0, start) + text.substring(end), TextRange(start)))
            } else if (start < text.length) {
                emit(TextFieldValue(text.substring(0, start) + text.substring(start + 1), TextRange(start)))
            }
            return true
        }
        Key.DirectionLeft -> {
            move(TextRange((start - 1).coerceAtLeast(0))); return true
        }
        Key.DirectionRight -> {
            move(TextRange((end + 1).coerceAtMost(text.length))); return true
        }
        Key.Enter, Key.NumPadEnter -> {
            emit(TextFieldValue(text.substring(0, start) + "\n" + text.substring(end), TextRange(start + 1)))
            return true
        }
    }
    if (event.isCtrlPressed) return false
    val codePoint = event.nativeKeyEvent.unicodeChar
    if (codePoint != 0) {
        val ch = codePoint.toChar()
        emit(TextFieldValue(text.substring(0, start) + ch + text.substring(end), TextRange(start + 1)))
        return true
    }
    return false
}

private const val MAX_LINE_HIGHLIGHT = 2000
