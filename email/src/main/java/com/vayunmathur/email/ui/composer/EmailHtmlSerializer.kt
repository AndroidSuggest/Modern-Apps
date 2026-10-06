package com.vayunmathur.email.ui.composer

import android.text.Spanned
import android.text.style.BulletSpan
import com.vayunmathur.library.ui.EmailAlignmentSpan
import com.vayunmathur.library.ui.EmailBlockQuoteSpan
import com.vayunmathur.library.ui.HeadingSpan
import com.vayunmathur.library.ui.HrSpan
import com.vayunmathur.library.ui.IndentSpan
import com.vayunmathur.library.ui.OrderedListSpan

/**
 * Serialize a Spanned (from HtmlEditor) to HTML, emitting <img src="cid:...">
 * for [CidImageSpan] and preserving bold/italic/underline/strike/links/lists/indent
 * plus rich formatting: headings, alignment, blockquote, hr, colors, font size/family, inline code.
 */

fun serializeEmailHtml(spanned: Spanned): String {
    if (spanned.isEmpty()) return ""
    return EmailHtmlParaRenderer(spanned).render()
}

private class EmailHtmlParaRenderer(private val spanned: Spanned) {
    private val len = spanned.length
    private val out = StringBuilder()
    private var currentList: String? = null
    private var orderedCounter = 0

    data class Para(val start: Int, val end: Int)

    fun render(): String {
        for (para in paras()) {
            renderPara(para)
        }
        closeList()
        val result = out.toString()
        return result.ifBlank { "<div><br></div>" }
    }

    private fun paras(): List<Para> {
        val list = mutableListOf<Para>()
        var pos = 0
        val text = spanned.toString()
        while (pos < len) {
            val nl = text.indexOf('\n', pos).let { if (it < 0) len else it }
            list.add(Para(pos, nl))
            pos = nl + 1
        }
        if (list.isEmpty() && len == 0) return emptyList()
        return list
    }

    private fun closeList() {
        if (currentList != null) {
            out.append("</${currentList}>")
            currentList = null
            orderedCounter = 0
        }
    }

    private fun hasBullet(p: Para): Boolean {
        return spanned.getSpans(p.start, p.end, BulletSpan::class.java).any {
            spanned.getSpanStart(it) < p.end && spanned.getSpanEnd(it) > p.start
        }
    }

    private fun orderedSpan(p: Para): OrderedListSpan? {
        return spanned.getSpans(p.start, p.end, OrderedListSpan::class.java).firstOrNull {
            spanned.getSpanStart(it) < p.end && spanned.getSpanEnd(it) > p.start
        }
    }

    private fun indentLevel(p: Para): Int {
        return spanned.getSpans(p.start, p.end, IndentSpan::class.java).firstOrNull {
            spanned.getSpanStart(it) < p.end && spanned.getSpanEnd(it) > p.start
        }?.level ?: 0
    }

    private fun headingLevel(p: Para): Int? {
        return spanned.getSpans(p.start, p.end, HeadingSpan::class.java).firstOrNull {
            spanned.getSpanStart(it) < p.end && spanned.getSpanEnd(it) > p.start
        }?.level
    }

    private fun alignment(p: Para): String? {
        return spanned.getSpans(p.start, p.end, EmailAlignmentSpan::class.java).firstOrNull {
            spanned.getSpanStart(it) < p.end && spanned.getSpanEnd(it) > p.start
        }?.alignmentCss
    }

    private fun isBlockquote(p: Para): Boolean {
        return spanned.getSpans(p.start, p.end, EmailBlockQuoteSpan::class.java).any {
            spanned.getSpanStart(it) < p.end && spanned.getSpanEnd(it) > p.start
        }
    }

    private fun isHr(p: Para): Boolean {
        return spanned.getSpans(p.start, p.end, HrSpan::class.java).any {
            spanned.getSpanStart(it) < p.end && spanned.getSpanEnd(it) > p.start
        }
    }

    private fun inlineHtmlFor(p: Para): String {
        if (p.start >= p.end) return ""
        return buildInlineHtml(spanned, p.start, p.end)
    }

    private fun renderPara(para: Para) {
        // Horizontal rule takes precedence
        if (isHr(para)) {
            closeList()
            out.append("<hr>")
            return
        }

        if (para.start >= para.end) {
            closeList()
            val lvl = indentLevel(para)
            if (lvl > 0) out.append("<div style=\"margin-left: ${lvl * 24}px\"><br></div>")
            else out.append("<br>")
            return
        }

        val inner = inlineHtmlFor(para).ifBlank { "<br>" }
        val style = ParaStyle(
            bullet = hasBullet(para),
            ordered = orderedSpan(para) != null,
            indentLvl = indentLevel(para),
            hLevel = headingLevel(para),
            align = alignment(para),
            blockquote = isBlockquote(para),
        )
        if (style.bullet || style.ordered) {
            renderListItem(style, inner)
        } else {
            renderBlock(style, inner)
        }
    }

    private data class ParaStyle(
        val bullet: Boolean,
        val ordered: Boolean,
        val indentLvl: Int,
        val hLevel: Int?,
        val align: String?,
        val blockquote: Boolean,
    )

    private fun renderListItem(style: ParaStyle, inner: String) {
        // List item – embed heading/blockquote/alignment inside <li> if present for minimal email-safe output
        val liInner: String = when {
            style.hLevel != null -> {
                val alignPart = if (style.align != null && style.align != "left") "text-align:${style.align};" else ""
                "<h${style.hLevel} style=\"margin:0;${alignPart}\">$inner</h${style.hLevel}>"
            }
            style.blockquote -> {
                val alignPart = if (style.align != null && style.align != "left") ";text-align:${style.align}" else ""
                "<blockquote style=\"border-left:2px solid #ccc;margin:0 0 0 8px;padding-left:8px$alignPart\">$inner</blockquote>"
            }
            style.align != null && style.align != "left" -> "<div style=\"text-align:${style.align}\">$inner</div>"
            else -> inner
        }
        val withIndent = if (style.indentLvl > 0) {
            "<div style=\"margin-left: ${style.indentLvl * 24}px\">$liInner</div>"
        } else {
            liInner
        }

        if (style.bullet) {
            openListIfNeeded("ul")
            out.append("<li>$withIndent</li>")
        } else {
            openListIfNeeded("ol")
            orderedCounter++
            out.append("<li>$withIndent</li>")
        }
    }

    private fun openListIfNeeded(kind: String) {
        if (currentList != kind) {
            closeList()
            out.append("<$kind>")
            currentList = kind
            orderedCounter = 0
        }
    }

    private fun renderBlock(style: ParaStyle, inner: String) {
        closeList()
        when {
            style.hLevel != null -> {
                val styles = mutableListOf<String>()
                styles.add("margin:0.5em 0")
                if (style.align != null && style.align != "left") styles.add("text-align:${style.align}")
                if (style.indentLvl > 0) styles.add("margin-left:${style.indentLvl * 24}px")
                out.append("<h${style.hLevel} style=\"${styles.joinToString(";")}\">$inner</h${style.hLevel}>")
            }
            style.blockquote -> {
                val styles = mutableListOf<String>()
                styles.add("border-left:2px solid #ccc")
                styles.add("margin:0 0 0 8px")
                styles.add("padding-left:8px")
                if (style.indentLvl > 0) styles.add("margin-left:${style.indentLvl * 24}px")
                if (style.align != null && style.align != "left") styles.add("text-align:${style.align}")
                out.append("<blockquote style=\"${styles.joinToString(";")}\">$inner</blockquote>")
            }
            else -> {
                if (style.align != null || style.indentLvl > 0) {
                    val styles = mutableListOf<String>()
                    if (style.align != null && style.align != "left") styles.add("text-align:${style.align}")
                    if (style.indentLvl > 0) styles.add("margin-left:${style.indentLvl * 24}px")
                    out.append("<div style=\"${styles.joinToString(";")}\">$inner</div>")
                } else {
                    out.append("<div>$inner</div>")
                }
            }
        }
    }
}
