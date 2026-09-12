package com.vayunmathur.library.ui

import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BulletSpan
import android.text.style.URLSpan
import androidx.core.text.HtmlCompat
import androidx.core.text.htmlEncode
import androidx.core.text.toHtml

fun serializeRich(spanned: Spanned): String {
    if (spanned.isEmpty()) return ""
    val editable = spanned as? Editable ?: SpannableStringBuilder(spanned)
    val paras = editable.paragraphsAll()
    if (paras.isEmpty()) return ""

    data class ParaMeta(
        val start: Int, val end: Int,
        val bullet: Boolean, val orderedNum: Int?,
        val indentLevel: Int, val inlineHtml: String, val isEmpty: Boolean,
    )

    fun paraInlineHtml(start: Int, end: Int): String {
        if (start >= end) return ""
        val slice = SpannableStringBuilder()
        slice.append(editable.subSequence(start, end))
        val raw = slice.toHtml(HtmlCompat.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE).trim()
        var inner = raw.replace(Regex("^<p[^>]*>"), "").replace(Regex("</p>\\s*$"), "").trim()
        if (inner.isEmpty()) {
            val txt = editable.subSequence(start, end).toString()
            if (txt.isBlank()) return ""
            return txt.htmlEncode()
        }
        return inner
    }

    val metas = paras.map { (s, e) ->
        val bullet = editable.getSpans(s, e, BulletSpan::class.java).any { sp -> editable.getSpanStart(sp) < e && editable.getSpanEnd(sp) > s }
        val orderedSpan = editable.getSpans(s, e, OrderedListSpan::class.java).firstOrNull { sp -> editable.getSpanStart(sp) < e && editable.getSpanEnd(sp) > s }
        val indent = editable.getSpans(s, e, IndentSpan::class.java).firstOrNull { sp -> editable.getSpanStart(sp) < e && editable.getSpanEnd(sp) > s }?.level ?: 0
        ParaMeta(s, e, bullet, orderedSpan?.number, indent, paraInlineHtml(s, e), s >= e)
    }

    val out = StringBuilder()
    var currentList: String? = null
    fun closeList() { if (currentList != null) { out.append("</${currentList}>"); currentList = null } }

    for (meta in metas) {
        if (meta.isEmpty) {
            closeList()
            if (meta.indentLevel > 0) out.append("<div style=\"margin-left: ${meta.indentLevel * 24}px\"><br></div>") else out.append("<br>")
            continue
        }
        val inline = meta.inlineHtml.ifBlank { "<br>" }
        val indentedContent = if (meta.indentLevel > 0) "<div style=\"margin-left: ${meta.indentLevel * 24}px\">$inline</div>" else inline
        when {
            meta.bullet -> {
                if (currentList != "ul") { closeList(); out.append("<ul>"); currentList = "ul" }
                out.append("<li>$indentedContent</li>")
            }
            meta.orderedNum != null -> {
                if (currentList != "ol") { closeList(); out.append("<ol>"); currentList = "ol" }
                out.append("<li>$indentedContent</li>")
            }
            else -> {
                closeList()
                if (meta.indentLevel > 0) out.append(indentedContent) else out.append("<div>$inline</div>")
            }
        }
    }
    closeList()
    val result = out.toString()
    return if (result.isBlank()) spanned.toHtml(HtmlCompat.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE) else result
}

internal fun urlSpanAt(e: Editable, start: Int, end: Int): URLSpan? {
    e.getSpans(start, maxOf(end, start), URLSpan::class.java).firstOrNull()?.let { return it }
    if (start == end && start > 0) e.getSpans(start - 1, start, URLSpan::class.java).firstOrNull()?.let { return it }
    return null
}

internal fun Editable.isFullyCovered(start: Int, end: Int, matches: (Any) -> Boolean): Boolean {
    if (start >= end) return false
    var pos = start
    while (pos < end) {
        val ok = getSpans(pos, pos + 1, Any::class.java).any { matches(it) && getSpanStart(it) <= pos && getSpanEnd(it) >= pos + 1 }
        if (!ok) return false
        pos++
    }
    return true
}

internal inline fun <reified T : Any> Editable.hasParaSpanInSelection(start: Int, end: Int): Boolean {
    var found = false
    forEachParagraph(start, end) { paraStart, paraEnd ->
        if (found) return@forEachParagraph
        if (paraEnd <= paraStart) return@forEachParagraph
        val spans = getSpans(paraStart, paraEnd, T::class.java)
        if (spans.any { getSpanStart(it) < paraEnd && getSpanEnd(it) > paraStart }) found = true
    }
    return found
}
