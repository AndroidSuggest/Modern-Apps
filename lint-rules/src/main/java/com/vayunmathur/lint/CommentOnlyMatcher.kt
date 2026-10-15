package com.vayunmathur.lint

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiRecursiveElementVisitor
import org.jetbrains.kotlin.psi.KtFile

/**
 * Shared comment-only matching for marker-based detectors.
 *
 * Several detectors historically used whole-file `KtFile.text.contains(MARKER)`,
 * which cannot tell a marker inside a `//` comment from the same text inside a
 * string literal — a string containing e.g. `"// TOAST EXCEPTION:"` would
 * silently opt the file out. All marker checks must go through here so they
 * only look at PSI comment elements.
 *
 * KDoc is covered: in the Kotlin PSI it surfaces as a [PsiComment] subtype,
 * so a single [PsiComment] walk sees `//`, block comments, and KDoc alike.
 */
object CommentOnlyMatcher {

    /**
     * All comment elements in [file] in source order.
     */
    fun commentsIn(file: KtFile): List<PsiComment> {
        val out = mutableListOf<PsiComment>()
        file.accept(
            object : PsiRecursiveElementVisitor() {
                override fun visitComment(comment: PsiComment) {
                    super.visitComment(comment)
                    out.add(comment)
                }
            },
        )
        return out
    }

    /**
     * Text ranges of every comment in [file], for offset-in-comment checks
     * (e.g. skipping `androidx.room.` matches that live in KDoc).
     */
    fun commentRangesIn(file: KtFile): List<TextRange> =
        commentsIn(file).map { it.textRange }

    /** True if any comment in [file] contains [marker] verbatim. */
    fun containsMarkerInComments(file: KtFile, marker: String): Boolean =
        commentsIn(file).any { it.text.contains(marker) }

    /** True if [offset] falls inside any of [ranges]. */
    fun isInRanges(offset: Int, ranges: List<TextRange>): Boolean =
        ranges.any { it.startOffset <= offset && offset < it.endOffset }
}
