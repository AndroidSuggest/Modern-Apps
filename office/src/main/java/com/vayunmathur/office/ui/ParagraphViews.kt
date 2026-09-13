package com.vayunmathur.office.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import com.vayunmathur.library.ui.ExpandVisibility
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.vayunmathur.office.odf.*
import com.vayunmathur.library.ui.odf.*

internal fun evalCondFormat(rules: List<OdfCondFormat>, numeric: Double?, text: String): OdfCondFormat? {
    val re = Regex("(value\\(\\)|cell-content\\(\\))\\s*(<=|>=|<>|=|<|>)\\s*(.+)")
    for (rule in rules) {
        val m = re.find(rule.condition.trim()) ?: continue
        val op = m.groupValues[2]
        val rhs = m.groupValues[3].trim()
        val matched = if (rhs.startsWith("\"")) {
            val rstr = rhs.trim('"')
            when (op) { "=" -> text == rstr; "<>" -> text != rstr; else -> false }
        } else {
            val rhsNum = rhs.toDoubleOrNull()
            if (numeric != null && rhsNum != null) when (op) {
                "<" -> numeric < rhsNum; "<=" -> numeric <= rhsNum; ">" -> numeric > rhsNum
                ">=" -> numeric >= rhsNum; "=" -> numeric == rhsNum; "<>" -> numeric != rhsNum; else -> false
            } else false
        }
        if (matched) return rule
    }
    return null
}

@Composable
private fun paragraphBaseStyle(style: ParagraphStyle): TextStyle = when (style) {
    ParagraphStyle.HEADING1 -> MaterialTheme.typography.headlineLarge
    ParagraphStyle.HEADING2 -> MaterialTheme.typography.headlineMedium
    ParagraphStyle.HEADING3 -> MaterialTheme.typography.headlineSmall
    ParagraphStyle.HEADING4 -> MaterialTheme.typography.titleLarge
    ParagraphStyle.BODY -> MaterialTheme.typography.bodyLarge
    ParagraphStyle.LIST_ITEM -> MaterialTheme.typography.bodyLarge
    ParagraphStyle.TABLE_HEADER -> MaterialTheme.typography.titleSmall
}

@Composable
fun ParagraphView(paragraph: OdfParagraph, searchQuery: String = "", fontSizeMultiplier: Float = 1f, nightTextColor: Color = Color.Unspecified) {
    val baseStyle = paragraphBaseStyle(paragraph.style)
    val prefix = listPrefixFor(paragraph)
    val hasLinks = paragraph.spans.any { it.href != null }
    val hasAnnotations = paragraph.spans.any { it.annotation != null }
    val context = LocalContext.current
    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
    val onHighlightColor = MaterialTheme.colorScheme.onTertiaryContainer
    val annotationColor = MaterialTheme.colorScheme.secondaryContainer
    val onAnnotationColor = MaterialTheme.colorScheme.onSecondaryContainer

    val annotatedString = buildAnnotatedString {
        if (prefix.isNotEmpty()) append(prefix)
        for (span in paragraph.spans) {
            val spanAnnotation = span.annotation
            if (spanAnnotation != null) {
                val start = length
                withStyle(SpanStyle(color = onAnnotationColor, background = annotationColor)) { append(span.text) }
                addStringAnnotation("ANNOTATION", "${spanAnnotation.author ?: ""}\n${spanAnnotation.paragraphs.joinToString("\n") { p -> p.spans.joinToString("") { it.text } }}", start, length)
                continue
            }
            val decorations = mutableListOf<TextDecoration>()
            if (span.underline) decorations.add(TextDecoration.Underline)
            if (span.strikethrough) decorations.add(TextDecoration.LineThrough)
            val rawFontSize = span.fontSize?.sp ?: baseStyle.fontSize
            val baseFontSize = if (rawFontSize != TextUnit.Unspecified) rawFontSize * fontSizeMultiplier else rawFontSize
            val effectiveFontSize = if ((span.superscript || span.subscript) && baseFontSize != TextUnit.Unspecified) baseFontSize * 0.7f else baseFontSize
            val spanColor = span.color
            val spanTextColor = when {
                spanColor != null -> Color(spanColor.toInt())
                nightTextColor != Color.Unspecified -> nightTextColor
                else -> Color.Unspecified
            }
            val spanStyle = SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                fontSize = effectiveFontSize,
                textDecoration = if (decorations.isNotEmpty()) TextDecoration.combine(decorations) else null,
                color = spanTextColor,
                background = span.backgroundColor?.let { Color(it.toInt()) } ?: Color.Unspecified,
                letterSpacing = span.letterSpacing?.sp ?: TextUnit.Unspecified,
                baselineShift = when { span.superscript -> BaselineShift.Superscript; span.subscript -> BaselineShift.Subscript; else -> null }
            )
            val shownText = when (span.textTransform) {
                "uppercase" -> span.text.uppercase()
                "lowercase" -> span.text.lowercase()
                "capitalize" -> span.text.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
                else -> span.text
            }
            if (searchQuery.isNotEmpty() && span.text.contains(searchQuery, ignoreCase = true)) {
                var remaining = span.text
                while (remaining.isNotEmpty()) {
                    val idx = remaining.indexOf(searchQuery, ignoreCase = true)
                    if (idx < 0) { linkOrPlain(span, spanStyle, remaining); break }
                    if (idx > 0) linkOrPlain(span, spanStyle, remaining.substring(0, idx))
                    withStyle(spanStyle.copy(color = onHighlightColor, background = highlightColor)) { append(remaining.substring(idx, idx + searchQuery.length)) }
                    remaining = remaining.substring(idx + searchQuery.length)
                }
            } else linkOrPlain(span, spanStyle, shownText)
        }
    }

    val indentDp = if (paragraph.marginLeft > 0 || paragraph.listLevel > 1) (paragraph.marginLeft + maxOf(0, paragraph.listLevel - 1) * 16f).dp else 0.dp
    val verticalPadding = when (paragraph.style) {
        ParagraphStyle.HEADING1 -> 12.dp; ParagraphStyle.HEADING2 -> 10.dp; ParagraphStyle.HEADING3 -> 8.dp; ParagraphStyle.HEADING4 -> 6.dp
        ParagraphStyle.BODY -> 2.dp; ParagraphStyle.LIST_ITEM -> 1.dp; ParagraphStyle.TABLE_HEADER -> 4.dp
    }
    val topPad = if (paragraph.marginTop > 0) paragraph.marginTop.dp else verticalPadding
    val bottomPad = if (paragraph.marginBottom > 0) paragraph.marginBottom.dp else verticalPadding
    val modifier = Modifier.fillMaxWidth()
        .then(if (indentDp > 0.dp) Modifier.padding(start = indentDp) else Modifier)
        .then(paragraph.borderColor?.let { Modifier.border(1.dp, Color(it.toInt())) } ?: Modifier)
        .then(paragraph.backgroundColor?.let { Modifier.background(Color(it.toInt())) } ?: Modifier)
        .padding(top = topPad, bottom = bottomPad)
    val lineHeight = paragraph.lineHeightPercent?.let { lh ->
        val fs = if (baseStyle.fontSize != TextUnit.Unspecified) baseStyle.fontSize else 16.sp
        fs * fontSizeMultiplier * lh
    } ?: TextUnit.Unspecified
    val scaledStyle = if (fontSizeMultiplier != 1f && baseStyle.fontSize != TextUnit.Unspecified) {
        baseStyle.copy(fontSize = baseStyle.fontSize * fontSizeMultiplier, textAlign = paragraph.alignment ?: TextAlign.Unspecified, lineHeight = lineHeight, color = if (nightTextColor != Color.Unspecified) nightTextColor else baseStyle.color)
    } else baseStyle.copy(textAlign = paragraph.alignment ?: TextAlign.Unspecified, lineHeight = lineHeight, color = if (nightTextColor != Color.Unspecified) nightTextColor else baseStyle.color)

    if (hasLinks || hasAnnotations) {
        var expandedAnnotation by remember { mutableStateOf<String?>(null) }
        Column {
            @Suppress("DEPRECATION")
            ClickableText(text = annotatedString, style = scaledStyle, modifier = modifier, onClick = { offset ->
                annotatedString.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { a ->
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, a.item.toUri())) } catch (_: Exception) {}; return@ClickableText
                }
                annotatedString.getStringAnnotations("ANNOTATION", offset, offset).firstOrNull()?.let { a ->
                    expandedAnnotation = if (expandedAnnotation == a.item) null else a.item
                }
            })
            ExpandVisibility(visible = expandedAnnotation != null) { expandedAnnotation?.let { AnnotationPopup(it) } }
        }
    } else Text(text = annotatedString, style = scaledStyle, modifier = modifier)
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.linkOrPlain(span: OdfSpan, style: SpanStyle, text: String) {
    val href = span.href
    if (href != null) {
        val s = length; withStyle(style) { append(text) }; addStringAnnotation("URL", href, s, length)
    } else withStyle(style) { append(text) }
}

@Composable
private fun AnnotationPopup(content: String) {
    val parts = content.split("\n", limit = 2)
    val author = parts[0].ifEmpty { null }
    val body = if (parts.size > 1) parts[1] else ""
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (author != null) Text(author, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
            if (body.isNotEmpty()) Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}
