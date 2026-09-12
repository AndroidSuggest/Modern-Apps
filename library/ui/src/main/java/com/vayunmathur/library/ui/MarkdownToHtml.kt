package com.vayunmathur.library.ui

// ---------------------------------------------------------------------------
// Markdown -> HTML conversion (for HTML consumers such as email).
// ---------------------------------------------------------------------------

private fun escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private val htmlLinkRegex = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")
private val htmlBoldStarRegex = Regex("\\*\\*(.+?)\\*\\*")
private val htmlBoldUnderRegex = Regex("__(.+?)__")
private val htmlStrikeRegex = Regex("~~(.+?)~~")
private val htmlItalicStarRegex = Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)")
private val htmlItalicUnderRegex = Regex("(?<!_)_(?!_)(.+?)(?<!_)_(?!_)")
private val htmlInlineCodeRegex = Regex("`([^`]+)`")

private fun inlineMarkdownToHtml(s: String): String {
    var t = escapeHtml(s)
    t = htmlLinkRegex.replace(t) { m -> "<a href=\"${m.groupValues[2]}\">${m.groupValues[1]}</a>" }
    t = htmlBoldStarRegex.replace(t) { "<b>${it.groupValues[1]}</b>" }
    t = htmlBoldUnderRegex.replace(t) { "<b>${it.groupValues[1]}</b>" }
    t = htmlStrikeRegex.replace(t) { "<s>${it.groupValues[1]}</s>" }
    t = htmlItalicStarRegex.replace(t) { "<i>${it.groupValues[1]}</i>" }
    t = htmlItalicUnderRegex.replace(t) { "<i>${it.groupValues[1]}</i>" }
    t = htmlInlineCodeRegex.replace(t) { "<code>${it.groupValues[1]}</code>" }
    return t
}

/**
 * Converts the markdown produced by [MarkdownEditor] into HTML suitable for an
 * email body. Handles headings, bold/italic/strikethrough/inline-code, links,
 * bullet/numbered lists, checkboxes, blockquotes, fenced code blocks and rules.
 */
fun markdownToHtml(md: String): String {
    val lines = md.split("\n")
    val sb = StringBuilder()
    var inUl = false
    var inOl = false

    fun closeLists() {
        if (inUl) { sb.append("</ul>"); inUl = false }
        if (inOl) { sb.append("</ol>"); inOl = false }
    }

    val headingRe = Regex("^(#{1,6})\\s+(.*)$")
    val checkboxRe = Regex("^\\s*- \\[([ xX])]\\s+(.*)$")
    val bulletRe = Regex("^\\s*[-*+]\\s+(.*)$")
    val numberedRe = Regex("^\\s*\\d+[.)]\\s+(.*)$")
    val quoteRe = Regex("^>\\s?(.*)$")

    var i = 0
    while (i < lines.size) {
        val line = lines[i].trimEnd()
        val trimmed = line.trimStart()

        val heading = headingRe.matchEntire(line)
        val checkbox = checkboxRe.matchEntire(line)
        val bullet = if (checkbox == null) bulletRe.matchEntire(line) else null
        val numbered = numberedRe.matchEntire(line)
        val quote = quoteRe.matchEntire(line)

        when {
            trimmed.startsWith("```") -> {
                closeLists()
                sb.append("<pre><code>")
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    sb.append(escapeHtml(lines[i])).append("\n")
                    i++
                }
                sb.append("</code></pre>")
                i++ // skip closing fence (if present)
            }
            trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                closeLists(); sb.append("<hr>"); i++
            }
            heading != null -> {
                closeLists()
                val level = heading.groupValues[1].length
                sb.append("<h$level>").append(inlineMarkdownToHtml(heading.groupValues[2])).append("</h$level>")
                i++
            }
            checkbox != null -> {
                if (inOl) { sb.append("</ol>"); inOl = false }
                if (!inUl) { sb.append("<ul>"); inUl = true }
                val checked = checkbox.groupValues[1].lowercase() == "x"
                sb.append("<li><input type=\"checkbox\" disabled")
                if (checked) sb.append(" checked")
                sb.append("> ").append(inlineMarkdownToHtml(checkbox.groupValues[2])).append("</li>")
                i++
            }
            bullet != null -> {
                if (inOl) { sb.append("</ol>"); inOl = false }
                if (!inUl) { sb.append("<ul>"); inUl = true }
                sb.append("<li>").append(inlineMarkdownToHtml(bullet.groupValues[1])).append("</li>")
                i++
            }
            numbered != null -> {
                if (inUl) { sb.append("</ul>"); inUl = false }
                if (!inOl) { sb.append("<ol>"); inOl = true }
                sb.append("<li>").append(inlineMarkdownToHtml(numbered.groupValues[1])).append("</li>")
                i++
            }
            quote != null -> {
                closeLists()
                sb.append("<blockquote>").append(inlineMarkdownToHtml(quote.groupValues[1])).append("</blockquote>")
                i++
            }
            line.isBlank() -> {
                closeLists(); sb.append("<br>"); i++
            }
            else -> {
                closeLists()
                sb.append("<p>").append(inlineMarkdownToHtml(line)).append("</p>")
                i++
            }
        }
    }
    closeLists()
    return "<html><body>$sb</body></html>"
}
