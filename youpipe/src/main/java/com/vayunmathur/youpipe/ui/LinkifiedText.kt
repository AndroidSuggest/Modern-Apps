package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.vayunmathur.library.ui.LocalTextStyle
import com.vayunmathur.library.ui.MaterialTheme

private val UrlRegex = Regex("""(https?://\S+|www\.\S+)""")

// Characters that are commonly trailing punctuation and NOT part of a URL
private val TrailingPunctuationChars = setOf(
    '.', ',', ')', ']', '}', '!', '?', ';', ':', '"', '\'', '>', '<'
)

/**
 * Splits raw regex match into clean URL and trailing punctuation.
 * Example: "https://example.com)." -> ("https://example.com", ").")
 */
private fun extractUrlAndTrailing(raw: String): Pair<String, String> {
    var url = raw
    var trailing = ""
    while (url.isNotEmpty() && url.last() in TrailingPunctuationChars) {
        // Special handling: if url ends with ')' but has no '(' inside, it's likely punctuation.
        // If it does have '(' we still strip trailing ) if counts are unbalanced — simple heuristic
        // still strips because YouTube links rarely end with ).
        trailing = url.last().toString() + trailing
        url = url.dropLast(1)
        // Prevent infinite stripping of empty or sole "www." etc.
        if (url.length < 4) break
    }
    return url to trailing
}

@Composable
fun LinkifiedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    val contentColor = LocalContentColor.current

    // Fix: ClickableText (foundation) does NOT resolve Color.Unspecified to LocalContentColor,
    // unlike Material3 Text. It falls back to Color.Black, making description/comments invisible
    // in dark mode where background is dark and onBackground is white, while links use primary
    // (light) so only links remain visible. Resolve explicitly.
    val resolvedStyle = remember(style, contentColor) {
        val baseColor = style.color
        if (baseColor == Color.Unspecified || baseColor == Color.Black) {
            style.copy(color = contentColor)
        } else {
            style
        }
    }

    val annotatedString = remember(text, linkColor) {
        buildAnnotatedString {
            var lastIndex = 0
            for (match in UrlRegex.findAll(text)) {
                val start = match.range.first
                val end = match.range.last + 1 // exclusive

                // Append plain text before this match
                if (start > lastIndex) {
                    append(text.substring(lastIndex, start))
                }

                val rawUrl = match.value
                val (cleanUrl, trailing) = extractUrlAndTrailing(rawUrl)

                if (cleanUrl.isNotEmpty()) {
                    val resolvedUrl = if (cleanUrl.startsWith("www.", ignoreCase = true)) {
                        "https://$cleanUrl"
                    } else {
                        cleanUrl
                    }

                    // Add URL annotation + link styling
                    addStringAnnotation(tag = "URL", annotation = resolvedUrl, start = length, end = length + cleanUrl.length)
                    pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                    append(cleanUrl)
                    pop()

                    // Append trailing punctuation as normal text
                    if (trailing.isNotEmpty()) {
                        append(trailing)
                    }
                } else {
                    // Fallback: raw was only punctuation, keep as-is
                    append(rawUrl)
                }

                lastIndex = end
            }

            // Append remainder
            if (lastIndex < text.length) {
                append(text.substring(lastIndex))
            }
        }
    }

    ClickableText(
        text = annotatedString,
        modifier = modifier,
        style = resolvedStyle,
        onClick = { offset ->
            val annotations = annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
            val url = annotations.firstOrNull()?.item
            if (url != null) {
                try {
                    uriHandler.openUri(url)
                } catch (_: Exception) {
                    // Ignore failures to open URI (no browser, malformed URL, etc.)
                }
            }
        }
    )
}
