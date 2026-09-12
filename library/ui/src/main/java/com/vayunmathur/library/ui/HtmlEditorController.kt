package com.vayunmathur.library.ui

import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan

/**
 * Rich formatting hooks for [HtmlEditor] plus the email subclass (CID images).
 *
 * The base state and operations live in [HtmlEditorControllerCore]; this keeps the
 * public [HtmlEditorController] name stable for the email module while staying under
 * the FileLength limit.
 */
/** Open so email module can subclass with CID images – minimal hook: public commitHtml(). */
open class HtmlEditorController(
    initialHtml: String = "",
    htmlSerializer: (Spanned) -> String = { s -> serializeRich(s) },
) : HtmlEditorControllerCore(initialHtml, htmlSerializer) {

    // ---- Rich formatting hooks (used by HtmlEditor + Email subclass) ----

    open fun toggleHeading(level: Int?) {
        val e = editText?.text ?: return
        e.forEachParagraph(selStart(), selEnd()) { paraStart, paraEnd ->
            if (paraEnd <= paraStart) return@forEachParagraph
            e.getSpans(paraStart, paraEnd, HeadingSpan::class.java).forEach { e.removeSpan(it) }
            if (level != null && level in 1..3) {
                e.setSpan(HeadingSpan(level), paraStart, paraEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            }
        }
        refresh()
    }

    open fun setAlignment(cssAlign: String?) {
        val e = editText?.text ?: return
        e.forEachParagraph(selStart(), selEnd()) { paraStart, paraEnd ->
            if (paraEnd <= paraStart) return@forEachParagraph
            e.getSpans(paraStart, paraEnd, EmailAlignmentSpan::class.java).forEach { e.removeSpan(it) }
            if (cssAlign != null && cssAlign.lowercase() != "left") {
                e.setSpan(EmailAlignmentSpan(cssAlign), paraStart, paraEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            }
        }
        refresh()
    }

    open fun toggleBlockquote() {
        val e = editText?.text ?: return
        val has = e.hasParaSpanInSelection<EmailBlockQuoteSpan>(selStart(), selEnd())
        e.forEachParagraph(selStart(), selEnd()) { paraStart, paraEnd ->
            if (paraEnd <= paraStart) return@forEachParagraph
            val existing = e.getSpans(paraStart, paraEnd, EmailBlockQuoteSpan::class.java)
            if (has) existing.forEach { e.removeSpan(it) }
            else if (existing.isEmpty()) e.setSpan(EmailBlockQuoteSpan(), paraStart, paraEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
        refresh()
    }

    open fun toggleInlineCode() = toggleCharSpan({ InlineCodeSpan() }) { it is InlineCodeSpan }

    open fun setTextColor(color: Int?) {
        if (color == null) {
            clearCharSpans { it is ForegroundColorSpan }
            refresh()
        } else {
            setCharSpan({ ForegroundColorSpan(color) }) { it is ForegroundColorSpan }
        }
    }

    open fun setHighlight(color: Int?) {
        if (color == null) {
            clearCharSpans { it is BackgroundColorSpan }
            refresh()
        } else {
            setCharSpan({ BackgroundColorSpan(color) }) { it is BackgroundColorSpan }
        }
    }

    open fun setFontSizeFactor(factor: Float?) {
        if (factor == null) {
            clearCharSpans { it is RelativeSizeSpan }
            refresh()
        } else {
            setCharSpan({ RelativeSizeSpan(factor) }) { it is RelativeSizeSpan }
        }
    }

    open fun setFontFamily(family: String?) {
        if (family == null) {
            clearCharSpans { it is FontFamilySpan || (it is TypefaceSpan && it.family != "monospace") }
            refresh()
        } else {
            setCharSpan({ FontFamilySpan(family) }) { it is FontFamilySpan || (it is TypefaceSpan && it.family != "monospace") }
        }
    }

    open fun insertHorizontalRule() {
        val edit = editText ?: return
        val editable = edit.text ?: return
        val cursor = edit.selectionStart.coerceIn(0, editable.length)
        updating = true
        try {
            // Insert newline, OBJECT REPLACEMENT with HrSpan, newline
            editable.insert(cursor, "\n￼\n")
            editable.setSpan(HrSpan(), cursor + 1, cursor + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            edit.setSelection((cursor + 3).coerceAtMost(editable.length))
        } finally {
            updating = false
        }
        refresh()
    }

    open fun clearFormatting() {
        val e = editText?.text ?: return
        val start = minOf(selStart(), selEnd())
        val end = maxOf(selStart(), selEnd())
        if (start >= end) return
        val toRemove = e.getSpans(start, end, Any::class.java).filter { sp ->
            sp is android.text.style.StyleSpan || sp is android.text.style.UnderlineSpan || sp is android.text.style.StrikethroughSpan ||
                sp is ForegroundColorSpan || sp is BackgroundColorSpan ||
                sp is RelativeSizeSpan || sp is TypefaceSpan ||
                sp is InlineCodeSpan || sp is FontFamilySpan
        }
        toRemove.forEach { sp ->
            val ss = e.getSpanStart(sp); val se = e.getSpanEnd(sp)
            e.removeSpan(sp)
            if (ss < start) e.setSpan(sp, ss, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (se > end) e.setSpan(sp, end, se, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        // Block-level in selection
        e.forEachParagraph(start, end) { paraStart, paraEnd ->
            if (paraEnd <= paraStart) return@forEachParagraph
            e.getSpans(paraStart, paraEnd, HeadingSpan::class.java).forEach { e.removeSpan(it) }
            e.getSpans(paraStart, paraEnd, EmailAlignmentSpan::class.java).forEach { e.removeSpan(it) }
            e.getSpans(paraStart, paraEnd, EmailBlockQuoteSpan::class.java).forEach { e.removeSpan(it) }
        }
        refresh()
    }

    open fun getCurrentHeadingLevel(): Int? {
        val e = editText?.text ?: return null
        @Suppress("UNUSED_EXPRESSION") html
        @Suppress("UNUSED_EXPRESSION") selectionStart
        @Suppress("UNUSED_EXPRESSION") selectionEnd
        var level: Int? = null
        var initialized = false
        e.forEachParagraph(selStart(), selEnd()) { paraStart, paraEnd ->
            if (paraEnd <= paraStart) return@forEachParagraph
            val l = e.getSpans(paraStart, paraEnd, HeadingSpan::class.java)
                .firstOrNull { e.getSpanStart(it) < paraEnd && e.getSpanEnd(it) > paraStart }?.level
            if (!initialized) {
                level = l
                initialized = true
            } else if (l != level) {
                level = null // mixed
            }
        }
        return level
    }

    open fun getCurrentAlignment(): String? {
        val e = editText?.text ?: return null
        @Suppress("UNUSED_EXPRESSION") html
        @Suppress("UNUSED_EXPRESSION") selectionStart
        @Suppress("UNUSED_EXPRESSION") selectionEnd
        val start = minOf(selStart(), selEnd())
        var css: String? = null
        // first paragraph only for display
        e.forEachParagraph(start, start) { paraStart, paraEnd ->
            val span = e.getSpans(paraStart, paraEnd, EmailAlignmentSpan::class.java).firstOrNull {
                e.getSpanStart(it) < paraEnd && e.getSpanEnd(it) > paraStart
            }
            css = span?.alignmentCss
        }
        return css
    }

    open fun isBlockquoteActive(): Boolean {
        val e = editText?.text ?: return false
        @Suppress("UNUSED_EXPRESSION") html
        @Suppress("UNUSED_EXPRESSION") selectionStart
        @Suppress("UNUSED_EXPRESSION") selectionEnd
        if (e.isEmpty()) return false
        var found = false
        e.forEachParagraph(selStart(), selEnd()) { paraStart, paraEnd ->
            if (found) return@forEachParagraph
            if (paraEnd <= paraStart) return@forEachParagraph
            val spans = e.getSpans(paraStart, paraEnd, EmailBlockQuoteSpan::class.java)
            if (spans.any { e.getSpanStart(it) < paraEnd && e.getSpanEnd(it) > paraStart }) found = true
        }
        return found
    }

    open fun isInlineCodeActive(): Boolean {
        val e = editText?.text ?: return false
        @Suppress("UNUSED_EXPRESSION") html
        @Suppress("UNUSED_EXPRESSION") selectionStart
        @Suppress("UNUSED_EXPRESSION") selectionEnd
        val start = minOf(selStart(), selEnd()).coerceIn(0, e.length)
        val end = maxOf(selStart(), selEnd()).coerceIn(0, e.length)
        if (start >= end) return false
        return e.isFullyCovered(start, end) { it is InlineCodeSpan }
    }

    open fun getCurrentTextColor(): Int? {
        val e = editText?.text ?: return null
        @Suppress("UNUSED_EXPRESSION") html
        @Suppress("UNUSED_EXPRESSION") selectionStart
        @Suppress("UNUSED_EXPRESSION") selectionEnd
        val start = minOf(selStart(), selEnd()).coerceIn(0, e.length)
        val end = maxOf(selStart(), selEnd()).coerceIn(0, e.length)
        if (start >= end) return null
        return e.getSpans(start, end, ForegroundColorSpan::class.java).firstOrNull()?.foregroundColor
    }

    open fun getCurrentHighlightColor(): Int? {
        val e = editText?.text ?: return null
        @Suppress("UNUSED_EXPRESSION") html
        @Suppress("UNUSED_EXPRESSION") selectionStart
        @Suppress("UNUSED_EXPRESSION") selectionEnd
        val start = minOf(selStart(), selEnd()).coerceIn(0, e.length)
        val end = maxOf(selStart(), selEnd()).coerceIn(0, e.length)
        if (start >= end) return null
        return e.getSpans(start, end, BackgroundColorSpan::class.java).firstOrNull()?.backgroundColor
    }

    open fun getCurrentFontSizeFactor(): Float? {
        val e = editText?.text ?: return null
        @Suppress("UNUSED_EXPRESSION") html
        @Suppress("UNUSED_EXPRESSION") selectionStart
        @Suppress("UNUSED_EXPRESSION") selectionEnd
        val start = minOf(selStart(), selEnd()).coerceIn(0, e.length)
        val end = maxOf(selStart(), selEnd()).coerceIn(0, e.length)
        if (start >= end) return null
        return e.getSpans(start, end, RelativeSizeSpan::class.java).firstOrNull()?.sizeChange
    }

    open fun getCurrentFontFamily(): String? {
        val e = editText?.text ?: return null
        @Suppress("UNUSED_EXPRESSION") html
        @Suppress("UNUSED_EXPRESSION") selectionStart
        @Suppress("UNUSED_EXPRESSION") selectionEnd
        val start = minOf(selStart(), selEnd()).coerceIn(0, e.length)
        val end = maxOf(selStart(), selEnd()).coerceIn(0, e.length)
        if (start >= end) return null
        e.getSpans(start, end, FontFamilySpan::class.java).firstOrNull()?.let { return it.familyName }
        e.getSpans(start, end, TypefaceSpan::class.java).firstOrNull { it !is InlineCodeSpan }?.let { return it.family }
        return null
    }

    override fun linkContext(): LinkContext? = computeLinkContext()

    private fun computeLinkContext(): LinkContext? {
        val e = editText?.text ?: return null
        @Suppress("UNUSED_EXPRESSION") html
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, e.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, e.length)
        val span = urlSpanAt(e, start, end)
        if (span != null) {
            val ss = e.getSpanStart(span).coerceIn(0, e.length)
            val se = e.getSpanEnd(span).coerceIn(0, e.length)
            return LinkContext(editing = true, text = e.substring(ss, se), url = span.url ?: "")
        }
        return if (start < end) LinkContext(editing = false, text = e.substring(start, end), url = "") else null
    }

    override fun applyLink(context: LinkContext, text: String, url: String) {
        val e = editText?.text ?: return
        if (url.isBlank()) return
        if (context.editing) {
            val start = minOf(selStart(), selEnd()).coerceIn(0, e.length)
            val end = maxOf(selStart(), selEnd()).coerceIn(0, e.length)
            val span = urlSpanAt(e, start, end) ?: return
            val ss = e.getSpanStart(span); val se = e.getSpanEnd(span)
            e.removeSpan(span)
            updating = true
            e.replace(ss, se, text)
            updating = false
            e.setSpan(URLSpan(url), ss, ss + text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            editText?.setSelection(ss + text.length)
        } else {
            val start = minOf(selStart(), selEnd()).coerceIn(0, e.length)
            val end = maxOf(selStart(), selEnd()).coerceIn(0, e.length)
            updating = true
            e.replace(start, end, text)
            updating = false
            val newEnd = start + text.length
            e.getSpans(start, newEnd, URLSpan::class.java).forEach { e.removeSpan(it) }
            e.setSpan(URLSpan(url), start, newEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            editText?.setSelection(newEnd)
        }
        refresh()
    }
}
