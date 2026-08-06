package com.vayunmathur.code.util

/**
 * A tiny, dependency-free Markdown → HTML converter (there is no Markdown library in the catalog).
 *
 * Supports headings, bold/italic, inline and fenced code, links, unordered/ordered lists and
 * blockquotes — enough for a readable preview. Pure and unit-tested; the WebView-based
 * [com.vayunmathur.code.ui.PreviewPage] wraps the returned fragment in a styled document.
 */
fun markdownToHtml(markdown: String): String {
    val out = StringBuilder()
    val lines = markdown.replace("\r\n", "\n").split("\n")

    var inCode = false
    val codeBuf = StringBuilder()
    var listType: String? = null
    val paraBuf = StringBuilder()

    fun closeList() {
        if (listType != null) {
            out.append("</").append(listType).append(">\n")
            listType = null
        }
    }

    fun flushParagraph() {
        if (paraBuf.isNotBlank()) {
            out.append("<p>").append(inlineMarkdown(paraBuf.toString().trim())).append("</p>\n")
        }
        paraBuf.setLength(0)
    }

    for (line in lines) {
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                out.append("<pre><code>").append(escapeHtml(codeBuf.toString())).append("</code></pre>\n")
                codeBuf.setLength(0)
                inCode = false
            } else {
                flushParagraph()
                closeList()
                inCode = true
            }
            continue
        }
        if (inCode) {
            codeBuf.append(line).append("\n")
            continue
        }

        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            flushParagraph()
            closeList()
            continue
        }

        val heading = HEADING.find(trimmed)
        if (heading != null) {
            flushParagraph(); closeList()
            val level = heading.groupValues[1].length
            out.append("<h").append(level).append(">")
                .append(inlineMarkdown(heading.groupValues[2]))
                .append("</h").append(level).append(">\n")
            continue
        }

        if (trimmed.startsWith(">")) {
            flushParagraph(); closeList()
            out.append("<blockquote>").append(inlineMarkdown(trimmed.removePrefix(">").trim())).append("</blockquote>\n")
            continue
        }

        val bullet = BULLET.find(trimmed)
        if (bullet != null) {
            flushParagraph()
            if (listType != "ul") { closeList(); out.append("<ul>\n"); listType = "ul" }
            out.append("<li>").append(inlineMarkdown(bullet.groupValues[1])).append("</li>\n")
            continue
        }

        val numbered = NUMBERED.find(trimmed)
        if (numbered != null) {
            flushParagraph()
            if (listType != "ol") { closeList(); out.append("<ol>\n"); listType = "ol" }
            out.append("<li>").append(inlineMarkdown(numbered.groupValues[1])).append("</li>\n")
            continue
        }

        if (paraBuf.isNotEmpty()) paraBuf.append(" ")
        paraBuf.append(trimmed)
    }

    if (inCode) out.append("<pre><code>").append(escapeHtml(codeBuf.toString())).append("</code></pre>\n")
    flushParagraph()
    closeList()
    return out.toString().trim()
}

private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^[-*+]\\s+(.*)$")
private val NUMBERED = Regex("^\\d+\\.\\s+(.*)$")

private val INLINE_CODE = Regex("`([^`]+)`")
private val BOLD = Regex("\\*\\*([^*]+)\\*\\*|__([^_]+)__")
private val ITALIC = Regex("\\*([^*]+)\\*|_([^_]+)_")
private val LINK = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")

/** Applies inline Markdown to already-plain text (escapes HTML first). */
private fun inlineMarkdown(text: String): String {
    var s = escapeHtml(text)
    s = INLINE_CODE.replace(s) { "<code>${it.groupValues[1]}</code>" }
    s = BOLD.replace(s) { "<strong>${it.groupValues[1].ifEmpty { it.groupValues[2] }}</strong>" }
    s = ITALIC.replace(s) { "<em>${it.groupValues[1].ifEmpty { it.groupValues[2] }}</em>" }
    s = LINK.replace(s) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
    return s
}

private fun escapeHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
