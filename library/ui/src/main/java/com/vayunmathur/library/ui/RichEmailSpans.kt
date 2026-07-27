package com.vayunmathur.library.ui

import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.MetricAffectingSpan
import android.text.style.QuoteSpan
import android.text.style.TypefaceSpan

/**
 * Extended spans for rich email formatting (headings, alignment, blockquote, code, hr, fonts).
 * Email-safe HTML is emitted via EmailHtmlSerializer.
 */

// ---------------------------------------------------------------------------
// Paragraph-level spans
// ---------------------------------------------------------------------------

class HeadingSpan(val level: Int) : MetricAffectingSpan() {
    val proportion: Float = when (level) {
        1 -> 1.6f
        2 -> 1.35f
        3 -> 1.15f
        else -> 1f
    }

    override fun updateDrawState(tp: TextPaint) {
        tp.textSize = tp.textSize * proportion
        tp.isFakeBoldText = true
    }

    override fun updateMeasureState(tp: TextPaint) {
        tp.textSize = tp.textSize * proportion
        tp.isFakeBoldText = true
    }
}

/**
 * Holds a CSS alignment string so we can emit `justify` which Layout.Alignment does not represent.
 * Rendering in EditText falls back to NORMAL/CENTER/OPPOSITE.
 */
class EmailAlignmentSpan(val cssAlign: String) : AlignmentSpan.Standard(
    when (cssAlign.lowercase()) {
        "center" -> Layout.Alignment.ALIGN_CENTER
        "right" -> Layout.Alignment.ALIGN_OPPOSITE
        "justify" -> Layout.Alignment.ALIGN_NORMAL // EditText does not render justify, but we emit CSS
        else -> Layout.Alignment.ALIGN_NORMAL
    }
) {
    val alignmentCss: String = cssAlign.lowercase()
}

/** Visual blockquote – reuses QuoteSpan drawing (left border) with a neutral color. */
class EmailBlockQuoteSpan : QuoteSpan(Color.GRAY)

// ---------------------------------------------------------------------------
// Inline-level spans
// ---------------------------------------------------------------------------

/** Monospace inline code – rendered as monospace in the editor; HTML adds background. */
class InlineCodeSpan : TypefaceSpan("monospace") {
    override fun updateDrawState(ds: TextPaint) {
        super.updateDrawState(ds)
        ds.typeface = Typeface.MONOSPACE
    }

    override fun updateMeasureState(paint: TextPaint) {
        super.updateMeasureState(paint)
        paint.typeface = Typeface.MONOSPACE
    }
}

/** Marker for a horizontal rule paragraph (object replacement char). */
class HrSpan

/** Font family – e.g., "sans-serif", "serif", "monospace". */
class FontFamilySpan(val familyName: String) : TypefaceSpan(familyName)

// ---------------------------------------------------------------------------
// Helpers for serializer
// ---------------------------------------------------------------------------

fun Layout.Alignment.toCssAlign(): String = when (this) {
    Layout.Alignment.ALIGN_CENTER -> "center"
    Layout.Alignment.ALIGN_OPPOSITE -> "right"
    else -> "left"
}

fun cssAlignToLayout(align: String): Layout.Alignment = when (align.lowercase()) {
    "center" -> Layout.Alignment.ALIGN_CENTER
    "right", "end" -> Layout.Alignment.ALIGN_OPPOSITE
    else -> Layout.Alignment.ALIGN_NORMAL
}
