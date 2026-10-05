package com.vayunmathur.library.image.decoders

import android.graphics.Color
import androidx.core.graphics.toColorInt

internal fun parseRgbComp(c: String): Int {
    val text = c.trim()
    if (text.endsWith(PERCENT_SUFFIX)) {
        val percent = text.removeSuffix(PERCENT_SUFFIX).toFloatOrNull()
            ?: NO_COMPONENT_FLOAT
        return (percent * MAX_CHANNEL_VALUE / PERCENT_SCALE)
            .toInt()
            .coerceIn(MIN_CHANNEL_VALUE, MAX_CHANNEL_VALUE)
    }
    return text.toFloatOrNull()?.toInt()?.coerceIn(MIN_CHANNEL_VALUE, MAX_CHANNEL_VALUE)
        ?: NO_COMPONENT_INT
}

internal const val NO_COMPONENT_FLOAT = 0f
internal const val NO_COMPONENT_INT = 0
internal const val MIN_CHANNEL_VALUE = 0
internal const val MAX_CHANNEL_VALUE = 255
internal const val PERCENT_SCALE = 100f

internal fun parseAlphaComp(c: String): Float {
    val text = c.trim()
    if (text.endsWith(PERCENT_SUFFIX)) {
        val percent = text.removeSuffix(PERCENT_SUFFIX).toFloatOrNull()
            ?: FULL_OPACITY
        return (percent / PERCENT_SCALE).coerceIn(NO_OPACITY, FULL_OPACITY)
    }
    return normalizeAlpha(text.toFloatOrNull() ?: FULL_OPACITY)
}

internal fun normalizeAlpha(value: Float): Float {
    if (value > FULL_OPACITY) {
        return (value / MAX_CHANNEL_VALUE).coerceIn(NO_OPACITY, FULL_OPACITY)
    }
    return value.coerceIn(NO_OPACITY, FULL_OPACITY)
}

internal const val NO_OPACITY = 0f

internal fun parseColorString(cs: String?): Int? {
    if (cs == null) return null
    val text = cs.trim()
    if (text.isEmpty()) return null
    val lower = text.lowercase()
    val keyword = keywordColor(lower)
    if (keyword != null || lower == COLOR_NONE || lower == COLOR_CURRENT_COLOR) return keyword
    parseHexColor(text)?.let { return it }
    parseFunctionalColor(text, lower)?.let { return it }
    val fallback = runCatching { text.toColorInt() }.getOrNull()
    return fallback ?: namedColor(lower)
}

internal fun keywordColor(lower: String): Int? =
    when (lower) {
        COLOR_NONE -> null
        COLOR_TRANSPARENT -> Color.TRANSPARENT
        COLOR_CURRENT_COLOR -> null
        else -> null
    }

internal const val COLOR_NONE = "none"
internal const val COLOR_TRANSPARENT = "transparent"
internal const val COLOR_CURRENT_COLOR = "currentcolor"

internal fun parseHexColor(text: String): Int? {
    if (!text.startsWith(HEX_PREFIX)) return null
    return runCatching { text.toColorInt() }.getOrNull()
}

internal const val HEX_PREFIX = "#"

internal fun parseFunctionalColor(text: String, lower: String): Int? {
    if (!lower.startsWith(RGB_FUNCTION) && !lower.startsWith(RGBA_FUNCTION)) return null
    return runCatching { functionalColorOrNull(text) }.getOrNull()
}

internal const val RGB_FUNCTION = "rgb("
internal const val RGBA_FUNCTION = "rgba("

internal fun functionalColorOrNull(text: String): Int? {
    val inner = text.substringAfter('(').substringBeforeLast(')').replace('/', ' ').trim()
    val parts = inner.split(COMPONENT_SEPARATOR_REGEX).filter { it.isNotEmpty() }
    if (parts.size < MIN_RGB_COMPONENTS) return null
    val red = parseRgbComp(parts[RGB_RED_INDEX])
    val green = parseRgbComp(parts[RGB_GREEN_INDEX])
    val blue = parseRgbComp(parts[RGB_BLUE_INDEX])
    val alpha = if (parts.size >= MIN_RGBA_COMPONENTS) {
        parseAlphaComp(parts[RGB_ALPHA_INDEX])
    } else {
        FULL_OPACITY
    }
    return Color.argb(
        (alpha * MAX_CHANNEL_VALUE).toInt().coerceIn(MIN_CHANNEL_VALUE, MAX_CHANNEL_VALUE),
        red,
        green,
        blue,
    )
}

internal val COMPONENT_SEPARATOR_REGEX = Regex("[\\s,]+")

internal const val MIN_RGB_COMPONENTS = 3
internal const val MIN_RGBA_COMPONENTS = 4
internal const val RGB_RED_INDEX = 0
internal const val RGB_GREEN_INDEX = 1
internal const val RGB_BLUE_INDEX = 2
internal const val RGB_ALPHA_INDEX = 3

internal fun namedColor(lower: String): Int? =
    when (lower) {
        COLOR_RED -> Color.RED
        COLOR_GREEN -> Color.GREEN
        COLOR_BLUE -> Color.BLUE
        COLOR_BLACK -> Color.BLACK
        COLOR_WHITE -> Color.WHITE
        COLOR_GRAY, COLOR_GREY -> Color.GRAY
        COLOR_YELLOW -> Color.YELLOW
        COLOR_CYAN, COLOR_AQUA -> Color.CYAN
        COLOR_MAGENTA, COLOR_FUCHSIA -> Color.MAGENTA
        else -> null
    }

internal const val COLOR_RED = "red"
internal const val COLOR_GREEN = "green"
internal const val COLOR_BLUE = "blue"
internal const val COLOR_BLACK = "black"
internal const val COLOR_WHITE = "white"
internal const val COLOR_GRAY = "gray"
internal const val COLOR_GREY = "grey"
internal const val COLOR_YELLOW = "yellow"
internal const val COLOR_CYAN = "cyan"
internal const val COLOR_AQUA = "aqua"
internal const val COLOR_MAGENTA = "magenta"
internal const val COLOR_FUCHSIA = "fuchsia"
