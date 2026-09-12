package com.vayunmathur.travel.ui

/** "★★★★★ · 8.7/10" from a star rating + review score (either may be absent). */
internal fun starLabel(rating: Long, reviewScore: Double): String {
    val stars = if (rating in 1..5) "★".repeat(rating.toInt()) else ""
    val score = if (reviewScore > 0) "${"%.1f".format(reviewScore)}/10" else ""
    return listOf(stars, score).filter { it.isNotBlank() }.joinToString(" · ")
}
