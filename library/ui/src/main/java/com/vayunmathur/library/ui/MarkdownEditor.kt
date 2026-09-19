package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.IntegrationInstructions
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.TextFieldValue
import com.vayunmathur.library.util.parseMarkdown
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.R

/**
 * A reusable Markdown body field: a [BasicTextField] that renders the markdown
 * live (bold shows bold, headings grow, …) via the shared [parseMarkdown]. The
 * caller owns the raw markdown via [value]/[onValueChange]; styled rendering is
 * derived for display only so the cursor stays aligned with the text.
 *
 * Pair it with a [MarkdownFormatToolbar] (typically in a Scaffold bottomBar,
 * shown only while [onFocusChanged] reports focus). The formatting logic is
 * shared so the notes, contacts, calendar and email editors never diverge. For
 * HTML consumers, convert with [markdownToHtml].
 */
@Composable
fun MarkdownEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val styled = value.copy(
        annotatedString = parseMarkdown(
            value.text,
            showMarkers = false,
            process = false,
            softWrap = false,
        )
    )
    BasicTextField(
        value = styled,
        onValueChange = { nv ->
            if (nv.text == value.text && nv.selection.collapsed) {
                tryToggleCheckbox(nv.selection.start, value)?.let { onValueChange(it); return@BasicTextField }
            }
            onValueChange(nv)
        },
        modifier = modifier.fillMaxWidth().onFocusChanged { onFocusChanged(it.isFocused) },
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = LocalContentColor.current),
        cursorBrush = SolidColor(LocalContentColor.current),
        decorationBox = { inner ->
            Box {
                if (value.text.isEmpty() && placeholder != null) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                    )
                }
                inner()
            }
        },
    )
}

/** [EditorFormatter] over a markdown [TextFieldValue]. */
class MarkdownFormatter(
    private val value: TextFieldValue,
    private val onValueChange: (TextFieldValue) -> Unit,
) : EditorFormatter {
    override val supported = setOf(
        EditorFormat.BOLD, EditorFormat.ITALIC, EditorFormat.STRIKETHROUGH,
        EditorFormat.BULLET, EditorFormat.LINK,
    )

    override fun isActive(format: EditorFormat): Boolean = when (format) {
        EditorFormat.BOLD -> isInlineFormatActive(value.text, value.selection, "**")
        EditorFormat.ITALIC -> isInlineFormatActive(value.text, value.selection, "*")
        EditorFormat.STRIKETHROUGH -> isInlineFormatActive(value.text, value.selection, "~~")
        EditorFormat.BULLET -> isLinePrefixActive(value.text, value.selection, "- ")
        else -> false
    }

    override fun toggle(format: EditorFormat) {
        val nv = when (format) {
            EditorFormat.BOLD -> toggleInlineFormat(value, "**")
            EditorFormat.ITALIC -> toggleInlineFormat(value, "*")
            EditorFormat.STRIKETHROUGH -> toggleInlineFormat(value, "~~")
            EditorFormat.BULLET -> toggleLinePrefix(value, "- ")
            else -> return
        }
        onValueChange(nv)
    }

    override fun linkContext(): LinkContext? = markdownLinkContext(value)

    override fun applyLink(context: LinkContext, text: String, url: String) =
        onValueChange(applyMarkdownLink(value, context, text, url))
}

/**
 * Shared bottom formatting toolbar driving a markdown [TextFieldValue]. Renders
 * the mandated base buttons via [EditorBaseButtons] plus markdown-specific extras
 * (inline code, headings, quote, numbered list, checkbox, code block, rule).
 */
@Composable
fun MarkdownFormatToolbar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatter = MarkdownFormatter(value, onValueChange)
    var showHeadingMenu by remember { mutableStateOf(false) }

    fun apply(transform: (TextFieldValue) -> TextFieldValue) = onValueChange(transform(value))

    EditorBottomBar(modifier, scrollable = true) {
        EditorBaseButtons(formatter)

        FormatIconButton(
            Icons.Filled.Code, "Inline code",
            active = isInlineFormatActive(value.text, value.selection, "`"),
        ) { apply { toggleInlineFormat(it, "`") } }

        Box {
            FormatIconButton(
                Icons.Filled.Title, "Heading",
                active = getActiveHeadingLevel(value.text, value.selection.start) != null,
            ) { showHeadingMenu = true }
            DropdownMenu(expanded = showHeadingMenu, onDismissRequest = { showHeadingMenu = false }) {
                for (level in 1..3) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.heading, level)) }, onClick = {
                        apply { insertHeading(it, level) }
                        showHeadingMenu = false
                    })
                }
            }
        }

        FormatIconButton(
            Icons.Filled.FormatQuote, "Quote",
            active = isLinePrefixActive(value.text, value.selection, "> "),
        ) { apply { toggleLinePrefix(it, "> ") } }

        FormatIconButton(
            Icons.Filled.FormatListNumbered, "Numbered list",
            active = isLinePrefixActive(value.text, value.selection, "1. "),
        ) { apply { toggleLinePrefix(it, "1. ") } }

        FormatIconButton(
            Icons.Filled.CheckBox, "Checkbox",
            active = isLinePrefixActive(value.text, value.selection, "- [ ] "),
        ) { apply { toggleLinePrefix(it, "- [ ] ") } }

        FormatIconButton(Icons.Filled.IntegrationInstructions, "Code block") { apply { insertCodeBlock(it) } }
        FormatIconButton(Icons.Filled.HorizontalRule, "Horizontal rule") { apply { insertHorizontalRule(it) } }
    }
}
