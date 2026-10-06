package com.vayunmathur.email.ui.composer

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import androidx.core.text.htmlEncode
import com.vayunmathur.library.ui.FontFamilySpan
import com.vayunmathur.library.ui.HrSpan
import com.vayunmathur.library.ui.InlineCodeSpan
import kotlin.math.min

internal fun buildInlineHtml(spanned: Spanned, start: Int, end: Int): String {
    val sb = StringBuilder()
    var i = start
    while (i < end) {
        val char = spanned[i]
        // CID image spans first
        val cidSpans = spanned.getSpans(i, i + 1, CidImageSpan::class.java).filter {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i
        }
        if (cidSpans.isNotEmpty()) {
            val cs = cidSpans.first()
            sb.append("<img src=\"cid:${cs.cid}\">")
            val spanEnd = min(spanned.getSpanEnd(cs), end)
            i = spanEnd
            continue
        }
        // HR spans – emit <hr> and advance
        val hrSpans = spanned.getSpans(i, i + 1, HrSpan::class.java).filter {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i
        }
        if (hrSpans.isNotEmpty()) {
            sb.append("<hr>")
            val spanEnd = min(spanned.getSpanEnd(hrSpans.first()), end)
            i = spanEnd
            continue
        }
        if (char == '\uFFFC') {
            i++
            continue
        }
        val nextChange = findNextSpanBoundary(spanned, i, end)
        val slice = spanned.subSequence(i, nextChange).toString()
        var piece = slice.htmlEncode()

        // Determine active inline spans at i
        val hasCode = spanned.getSpans(i, i + 1, InlineCodeSpan::class.java).any {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i
        }
        val hasBold = spanned.getSpans(i, i + 1, StyleSpan::class.java).any {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i && (it.style and Typeface.BOLD) != 0
        }
        val hasItalic = spanned.getSpans(i, i + 1, StyleSpan::class.java).any {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i && (it.style and Typeface.ITALIC) != 0
        }
        val hasUnderline = spanned.getSpans(i, i + 1, UnderlineSpan::class.java).any {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i
        }
        val hasStrike = spanned.getSpans(i, i + 1, StrikethroughSpan::class.java).any {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i
        }
        val urlSpan = spanned.getSpans(i, i + 1, URLSpan::class.java).firstOrNull {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i
        }
        val fgSpan = spanned.getSpans(i, i + 1, ForegroundColorSpan::class.java).firstOrNull {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i
        }
        val bgSpan = spanned.getSpans(i, i + 1, BackgroundColorSpan::class.java).firstOrNull {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i
        }
        val sizeSpan = spanned.getSpans(i, i + 1, RelativeSizeSpan::class.java).firstOrNull {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i
        }
        // Font family: our custom FontFamilySpan has priority; fallback to TypefaceSpan with family != monospace and not InlineCode
        val fontFamilySpan = spanned.getSpans(i, i + 1, FontFamilySpan::class.java).firstOrNull {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i
        }
        val typefaceFamilySpan = spanned.getSpans(i, i + 1, TypefaceSpan::class.java).firstOrNull {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i && it !is InlineCodeSpan
        }

        // Build nesting inside-out: innermost first (code), outermost last (color)
        if (hasCode) {
            piece = "<code style=\"font-family:monospace;background:#f5f5f5;padding:1px 4px;border-radius:3px\">$piece</code>"
        }
        if (hasStrike) piece = "<s>$piece</s>"
        if (hasUnderline) piece = "<u>$piece</u>"
        if (hasItalic) piece = "<i>$piece</i>"
        if (hasBold) piece = "<b>$piece</b>"
        if (urlSpan != null) {
            piece = "<a href=\"${escapeAttr(urlSpan.url ?: "")}\">$piece</a>"
        }
        // Font family
        val familyName: String? = fontFamilySpan?.familyName ?: typefaceFamilySpan?.family
        if (familyName != null) {
            val cssFamily = when (familyName.lowercase()) {
                "monospace" -> "monospace"
                "serif" -> "serif"
                "sans-serif", "sans_serif", "sans" -> "sans-serif"
                else -> familyName
            }
            piece = "<span style=\"font-family:${escapeAttr(cssFamily)}\">$piece</span>"
        }
        if (sizeSpan != null) {
            val factor = sizeSpan.sizeChange
            // Emit em-based size, clamp for email safety. Locale.ROOT: this is a CSS
            // value, a comma decimal separator would make it invalid.
            val sizeStr = "${String.format(java.util.Locale.ROOT, "%.2f", factor)}em"
            piece = "<span style=\"font-size:$sizeStr\">$piece</span>"
        }
        if (bgSpan != null) {
            val hex = colorToHex(bgSpan.backgroundColor)
            piece = "<span style=\"background-color:$hex\">$piece</span>"
        }
        if (fgSpan != null) {
            val hex = colorToHex(fgSpan.foregroundColor)
            piece = "<span style=\"color:$hex\">$piece</span>"
        }

        sb.append(piece)
        i = nextChange
    }
    return sb.toString().replace("\n", "<br>")
}

internal fun findNextSpanBoundary(spanned: Spanned, from: Int, end: Int): Int {
    var next = end
    val spans = spanned.getSpans(from, end, Any::class.java)
    for (sp in spans) {
        val s = spanned.getSpanStart(sp)
        val e = spanned.getSpanEnd(sp)
        if (s > from && s < next) next = s
        if (e > from && e < next) next = e
    }
    if (next == from) next = from + 1
    return min(next, end)
}

internal fun escapeAttr(value: String): String {
    return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}

internal fun colorToHex(color: Int): String {
    // Strip alpha, produce #RRGGBB
    return String.format("#%06X", 0xFFFFFF and color)
}
