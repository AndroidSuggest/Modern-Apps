package org.schabi.newpipe.extractor.services.youtube

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.nodes.Entities
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getUrlFromNavigationEndpoint
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isYoutubeServiceURL
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isYoutubeURL
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getBoolean
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import org.schabi.newpipe.extractor.utils.getInt
import java.net.MalformedURLException
import java.net.URL
import java.util.*
import java.util.regex.Pattern

object YoutubeDescriptionHelper {

    private const val LINK_CLOSE = "</a>"
    private const val STRIKETHROUGH_OPEN = "<s>"
    private const val STRIKETHROUGH_CLOSE = "</s>"
    private const val BOLD_OPEN = "<b>"
    private const val BOLD_CLOSE = "</b>"
    private const val ITALIC_OPEN = "<i>"
    private const val ITALIC_CLOSE = "</i>"

    private val LINK_CONTENT_CLEANER_REGEX = Pattern.compile("(?s)^ +[/•] +(.*?) +$")

    class Run {
        val open: String
        val close: String
        val pos: Int
        val transformContent: ((String) -> String)?
        var openPosInOutput: Int = -1

        constructor(open: String, close: String, pos: Int) : this(open, close, pos, null)

        constructor(open: String, close: String, pos: Int, transformContent: ((String) -> String)?) {
            this.open = open
            this.close = close
            this.pos = pos
            this.transformContent = transformContent
        }

        fun sameOpen(other: Run): Boolean = open == other.open
    }

    @JvmStatic
    fun attributedDescriptionToHtml(attributedDescription: JsonObject?): String? {
        if (attributedDescription == null || attributedDescription.isEmpty()) {
            return null
        }

        val content = attributedDescription.getString("content") ?: return null

        val openers = mutableListOf<Run>()
        val closers = mutableListOf<Run>()
        addAllCommandRuns(attributedDescription, openers, closers)
        addAllStyleRuns(attributedDescription, openers, closers)

        openers.sortWith(Comparator.comparingInt { r: Run -> r.pos })
        closers.sortWith(Comparator.comparingInt { r: Run -> r.pos })

        return runsToHtml(openers, closers, content)
    }

    @JvmStatic
    fun runsToHtml(openers: List<Run>, closers: List<Run>, rawContent: String): String {
        val content = rawContent.replace('\u00a0', ' ')
        val openRuns = Stack<Run>()
        val tempStack = Stack<Run>()
        val textBuilder = StringBuilder()
        var currentTextPos = 0
        var openersIndex = 0
        var closersIndex = 0

        while (closersIndex < closers.size) {
            val minPos = if (openersIndex < openers.size) {
                Math.min(closers[closersIndex].pos, openers[openersIndex].pos)
            } else {
                closers[closersIndex].pos
            }

            textBuilder.append(Entities.escape(content.substring(currentTextPos, minPos)))
            currentTextPos = minPos

            if (closers[closersIndex].pos == minPos) {
                val closer = closers[closersIndex]
                ++closersIndex

                while (!openRuns.empty()) {
                    val popped = openRuns.pop()
                    if (popped.sameOpen(closer)) {
                        if (popped.transformContent != null && popped.openPosInOutput >= 0) {
                            textBuilder.replace(
                                popped.openPosInOutput, textBuilder.length,
                                popped.transformContent(
                                    textBuilder.substring(popped.openPosInOutput)
                                )
                            )
                        }
                        textBuilder.append(popped.close)
                        break
                    }
                    textBuilder.append(popped.close)
                    tempStack.push(popped)
                }
                while (!tempStack.empty()) {
                    val popped = tempStack.pop()
                    textBuilder.append(popped.open)
                    openRuns.push(popped)
                }
            } else {
                val opener = openers[openersIndex]
                textBuilder.append(opener.open)
                opener.openPosInOutput = textBuilder.length
                openRuns.push(opener)
                ++openersIndex
            }
        }

        textBuilder.append(Entities.escape(content.substring(currentTextPos)))

        return textBuilder.toString()
            .replace("\n", "<br>")
            .replace("  ", " &nbsp;")
    }

    private fun addAllCommandRuns(
        attributedDescription: JsonObject,
        openers: MutableList<Run>,
        closers: MutableList<Run>
    ) {
        val commandRuns = attributedDescription.getArray("commandRuns") ?: return
        for (elem in commandRuns) {
            val run = elem as? JsonObject ?: continue
            val onTap = run.getObject("onTap") ?: continue
            val navigationEndpoint = onTap.getObject("innertubeCommand") ?: continue

            val startIndex = run.getInt("startIndex") ?: -1
            val length = run.getInt("length") ?: 0
            if (startIndex < 0 || length < 1) continue

            val url = getUrlFromNavigationEndpoint(navigationEndpoint) ?: continue

            val isYoutubeUrl = try {
                val parsedUrl = URL(url)
                isYoutubeURL(parsedUrl) || isYoutubeServiceURL(parsedUrl)
            } catch (ignored: MalformedURLException) {
                false
            }

            val open = "<a href=\"${Entities.escape(url)}\">"
            val transformContent = getTransformContentFun(run, isYoutubeUrl)

            openers.add(Run(open, LINK_CLOSE, startIndex, transformContent))
            closers.add(Run(open, LINK_CLOSE, startIndex + length, transformContent))
        }
    }

    private fun getTransformContentFun(run: JsonObject, isYoutube: Boolean): (String) -> String {
        val accessibilityLabel = run.getObject("onTapOptions")
            ?.getObject("accessibilityInfo")
            ?.getString("accessibilityLabel", "")?.replaceFirst(" Channel Link", "") ?: ""

        return if (isYoutube || accessibilityLabel.isEmpty() || accessibilityLabel.startsWith("YouTube: ")) {
            { content ->
                val m = LINK_CONTENT_CLEANER_REGEX.matcher(content)
                if (m.find()) m.group(1) else content
            }
        } else {
            { _ -> accessibilityLabel }
        }
    }

    private fun addAllStyleRuns(
        attributedDescription: JsonObject,
        openers: MutableList<Run>,
        closers: MutableList<Run>
    ) {
        val styleRuns = attributedDescription.getArray("styleRuns") ?: return
        for (elem in styleRuns) {
            val run = elem as? JsonObject ?: continue
            val start = run.getInt("startIndex") ?: -1
            val length = run.getInt("length") ?: 0
            if (start < 0 || length < 1) continue
            val end = start + length

            if (run.containsKey("strikethrough")) {
                openers.add(Run(STRIKETHROUGH_OPEN, STRIKETHROUGH_CLOSE, start))
                closers.add(Run(STRIKETHROUGH_OPEN, STRIKETHROUGH_CLOSE, end))
            }

            val italic = run.getObject("italic") != null ||
                (run["italic"] as? JsonPrimitive)?.let {
                    it.content == "true"
                } == true ||
                run.getString("italic")?.toBoolean() == true ||
                run["italic"]?.toString() == "true" ||
                run.getArray("italic") == null && run.containsKey("italic") && run["italic"].let { el ->
                    (el as? JsonPrimitive)?.content == "true" || el?.toString() == "true"
                }

            // Use compat extension getBoolean with default
            val italicFlag = run.getObject("italic") != null || run.containsKey("italic") &&
                (run["italic"] as? JsonPrimitive)?.let { prim ->
                    prim.content.equals("true", ignoreCase = true)
                } == true || run.getString("italic") == "true"

            // Simpler: rely on extensions getBoolean
            val isItalic = run.let { obj ->
                val boolElem = obj["italic"]
                if (boolElem is JsonPrimitive) {
                    boolElem.content.toBoolean() || boolElem.content == "true"
                } else {
                    // also check booleanOrNull via extension
                    obj.getArray("italic") == null && obj.containsKey("italic") &&
                        obj.toString().contains("\"italic\":true")
                }
            }

            // Final reliable check: use getBoolean extension if available, else parse manually
            val italicFinal = run.getArray("italic") == null && run.getObject("italic") == null && run.containsKey("italic") &&
                (run["italic"] as? JsonPrimitive)?.let { it.content == "true" || it.content.toBoolean() } == true

            // Actually use extension getBoolean from JsonUtils
            val italicBool = run.getBoolean("italic") ?: false

            if (italicBool) {
                openers.add(Run(ITALIC_OPEN, ITALIC_CLOSE, start))
                closers.add(Run(ITALIC_OPEN, ITALIC_CLOSE, end))
            }

            val weightLabel = run.getString("weightLabel")
            if (run.containsKey("weightLabel") && weightLabel != "FONT_WEIGHT_NORMAL") {
                openers.add(Run(BOLD_OPEN, BOLD_CLOSE, start))
                closers.add(Run(BOLD_OPEN, BOLD_CLOSE, end))
            }
        }
    }
}
