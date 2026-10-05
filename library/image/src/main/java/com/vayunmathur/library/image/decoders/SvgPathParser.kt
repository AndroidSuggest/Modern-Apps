package com.vayunmathur.library.image.decoders

import android.graphics.Path

internal const val MOVE_COMMANDS = "Mm"
internal const val CLOSE_COMMANDS = "Zz"
internal const val LINE_COMMANDS = "Ll"
internal const val HORIZONTAL_COMMANDS = "Hh"
internal const val VERTICAL_COMMANDS = "Vv"
internal const val CUBIC_COMMANDS = "Cc"
internal const val SMOOTH_CUBIC_COMMANDS = "Ss"
internal const val QUADRATIC_COMMANDS = "Qq"
internal const val SMOOTH_QUADRATIC_COMMANDS = "Tt"
internal const val ARC_COMMANDS = "Aa"
internal const val ALL_COMMANDS = "MmZzLlHhVvCcSsQqTtAa"

internal const val SEPARATOR_COMMA = ','
internal const val SEPARATOR_DOT = '.'
internal const val SEPARATOR_PLUS = '+'
internal const val SEPARATOR_MINUS = '-'
internal const val NO_COMMAND = ' '
internal const val FLAG_ZERO = '0'
internal const val FLAG_ONE = '1'

internal const val NO_COORDINATE = 0f
internal const val PAIR_SIZE = 2
internal const val LINE_POINTS = 2
internal const val CUBIC_POINTS = 6
internal const val SMOOTH_CUBIC_POINTS = 4
internal const val QUADRATIC_POINTS = 4
internal const val SMOOTH_QUADRATIC_POINTS = 2
internal const val ARC_POINTS = 7

internal const val CUBIC_SMOOTH_PREVIOUS = 'C'
internal const val CUBIC_SMOOTH_CONTINUATION = 'S'
internal const val QUAD_SMOOTH_PREVIOUS = 'Q'
internal const val QUAD_SMOOTH_CONTINUATION = 'T'

internal class PathDataParser(private val data: String) {
    private var index = 0
    private val length = data.length
    private var cursorX = NO_COORDINATE
    private var cursorY = NO_COORDINATE
    private var startX = NO_COORDINATE
    private var startY = NO_COORDINATE
    private var lastCubicX2 = NO_COORDINATE
    private var lastCubicY2 = NO_COORDINATE
    private var lastQuadX1 = NO_COORDINATE
    private var lastQuadY1 = NO_COORDINATE
    var command: Char = NO_COMMAND
        internal set

    val path = Path()

    fun parse(): Path {
        while (hasMoreInput()) {
            skipSeparators()
            if (index >= length) break
            if (consumeCommand()) {
                dispatchCommand()
            }
        }
        return path
    }

    private fun hasMoreInput(): Boolean = index < length

    private fun consumeCommand(): Boolean {
        val char = data[index]
        if (char in ALL_COMMANDS) {
            command = char
            index++
            return true
        }
        if (peekIsNumberStart()) {
            if (command != NO_COMMAND) return true
        }
        index++
        return false
    }

    private fun dispatchCommand() {
        when (command.uppercaseChar()) {
            'M' -> applyMove(command.isLowerCase())
            'L' -> applyLine()
            'H' -> applyHorizontal()
            'V' -> applyVertical()
            'C' -> applyCubic(command.isLowerCase())
            'S' -> applySmoothCubic(command.isLowerCase())
            'Q' -> applyQuadratic(command.isLowerCase())
            'T' -> applySmoothQuadratic(command.isLowerCase())
            'A' -> applyArc(command.isLowerCase())
            'Z' -> applyClose()
        }
    }

    fun isRelative(): Boolean = command.isLowerCase()

    fun absoluteX(local: Float): Float =
        if (isRelative()) cursorX + local else local

    fun absoluteY(local: Float): Float =
        if (isRelative()) cursorY + local else local

    fun moveCursorTo(x: Float, y: Float) {
        cursorX = x
        cursorY = y
    }

    fun cursorPosition(): Pair<Float, Float> = cursorX to cursorY

    fun subpathStart(): Pair<Float, Float> = startX to startY

    fun recordSubpathStart(x: Float, y: Float) {
        startX = x
        startY = y
    }

    fun cubicControl(): Pair<Float, Float> = lastCubicX2 to lastCubicY2

    fun recordCubicControl(x: Float, y: Float) {
        lastCubicX2 = x
        lastCubicY2 = y
    }

    fun quadControl(): Pair<Float, Float> = lastQuadX1 to lastQuadY1

    fun recordQuadControl(x: Float, y: Float) {
        lastQuadX1 = x
        lastQuadY1 = y
    }

    fun skipSeparators() {
        while (index < length && isSeparator(data[index])) {
            index++
        }
    }

    private fun isSeparator(char: Char): Boolean =
        char.isWhitespace() || char == SEPARATOR_COMMA

    fun peekIsNumberStart(): Boolean {
        var scan = index
        while (scan < length && isSeparator(data[scan])) {
            scan++
        }
        if (scan >= length) return false
        val char = data[scan]
        return char.isDigit() ||
            char == SEPARATOR_DOT ||
            char == SEPARATOR_PLUS ||
            char == SEPARATOR_MINUS
    }

    fun parseNumber(): Float? {
        skipSeparators()
        if (index >= length) return null
        val match = leadingNumberRegex.find(data.substring(index))
        if (match == null || match.range.first != 0) return null
        val value = match.value.toFloatOrNull()
        index += match.value.length
        return value
    }

    fun parseFlag(): Int? {
        skipSeparators()
        if (index >= length) return null
        val char = data[index]
        if (char != FLAG_ZERO && char != FLAG_ONE) return null
        index++
        return char - FLAG_ZERO
    }

    fun readPoint(): Pair<Float, Float>? {
        val x = parseNumber() ?: return null
        val y = parseNumber() ?: return null
        return x to y
    }
}

internal fun PathDataParser.applyMove(relative: Boolean) {
    var first = true
    var keepGoing = true
    while (keepGoing && peekIsNumberStart()) {
        val point = readPoint()
        if (point == null) {
            keepGoing = false
        } else {
            first = applyMovePoint(point, relative, first)
        }
    }
}

internal fun PathDataParser.applyMovePoint(point: Pair<Float, Float>, relative: Boolean, first: Boolean): Boolean {
    val absolute = absolutePair(point.first, point.second, relative)
    if (first) {
        path.moveTo(absolute.first, absolute.second)
        recordSubpathStart(absolute.first, absolute.second)
    } else {
        path.lineTo(absolute.first, absolute.second)
    }
    moveCursorTo(absolute.first, absolute.second)
    command = if (relative) MOVE_AS_LINE_RELATIVE else MOVE_AS_LINE_ABSOLUTE
    return false
}

internal const val MOVE_AS_LINE_RELATIVE = 'l'
internal const val MOVE_AS_LINE_ABSOLUTE = 'L'

internal fun PathDataParser.applyLine() {
    var keepGoing = true
    while (keepGoing && peekIsNumberStart()) {
        val point = readPoint()
        if (point == null) {
            keepGoing = false
        } else {
            applyLinePoint(point)
        }
    }
}

internal fun PathDataParser.applyLinePoint(point: Pair<Float, Float>) {
    val x = absoluteX(point.first)
    val y = absoluteY(point.second)
    path.lineTo(x, y)
    moveCursorTo(x, y)
}

internal fun PathDataParser.applyHorizontal() {
    var keepGoing = true
    while (keepGoing && peekIsNumberStart()) {
        val x = parseNumber()
        if (x == null) {
            keepGoing = false
        } else {
            applyHorizontalValue(x)
        }
    }
}

internal fun PathDataParser.applyHorizontalValue(x: Float) {
    val absolute = absoluteX(x)
    val (_, y) = cursorPosition()
    path.lineTo(absolute, y)
    moveCursorTo(absolute, y)
}

internal fun PathDataParser.applyVertical() {
    var keepGoing = true
    while (keepGoing && peekIsNumberStart()) {
        val y = parseNumber()
        if (y == null) {
            keepGoing = false
        } else {
            applyVerticalValue(y)
        }
    }
}

internal fun PathDataParser.applyVerticalValue(y: Float) {
    val (x, _) = cursorPosition()
    val absolute = absoluteY(y)
    path.lineTo(x, absolute)
    moveCursorTo(x, absolute)
}

internal fun PathDataParser.applyCubic(relative: Boolean) {
    var keepGoing = true
    while (keepGoing && peekIsNumberStart()) {
        val values = readNumbers(CUBIC_POINTS)
        if (values == null) {
            keepGoing = false
        } else {
            applyCubicValues(values, relative)
        }
    }
}

internal fun PathDataParser.applyCubicValues(values: List<Float>, relative: Boolean) {
    val (x1, y1) = absolutePair(values[CUBIC_X1_INDEX], values[CUBIC_Y1_INDEX], relative)
    val (x2, y2) = absolutePair(values[CUBIC_X2_INDEX], values[CUBIC_Y2_INDEX], relative)
    val (x, y) = absolutePair(values[CUBIC_X_INDEX], values[CUBIC_Y_INDEX], relative)
    path.cubicTo(x1, y1, x2, y2, x, y)
    recordCubicControl(x2, y2)
    moveCursorTo(x, y)
    command = CUBIC_COMMAND
}

internal const val CUBIC_COMMAND = 'C'
internal const val CUBIC_X1_INDEX = 0
internal const val CUBIC_Y1_INDEX = 1
internal const val CUBIC_X2_INDEX = 2
internal const val CUBIC_Y2_INDEX = 3
internal const val CUBIC_X_INDEX = 4
internal const val CUBIC_Y_INDEX = 5

internal fun PathDataParser.absolutePair(x: Float, y: Float, relative: Boolean): Pair<Float, Float> =
    if (relative) {
        val (cursorX, cursorY) = cursorPosition()
        cursorX + x to cursorY + y
    } else {
        x to y
    }

internal fun PathDataParser.readNumbers(count: Int): List<Float>? {
    val values = mutableListOf<Float>()
    repeat(count) {
        values += parseNumber() ?: return null
    }
    return values
}

internal fun PathDataParser.applySmoothCubic(relative: Boolean) {
    var keepGoing = true
    while (keepGoing && peekIsNumberStart()) {
        val values = readNumbers(SMOOTH_CUBIC_POINTS)
        if (values == null) {
            keepGoing = false
        } else {
            applySmoothCubicValues(values, relative)
        }
    }
}

internal fun PathDataParser.applySmoothCubicValues(values: List<Float>, relative: Boolean) {
    val (control1X, control1Y) = reflectedCubicControl()
    val (x2, y2) = absolutePair(values[SMOOTH_CUBIC_X2_INDEX], values[SMOOTH_CUBIC_Y2_INDEX], relative)
    val (x, y) = absolutePair(values[SMOOTH_CUBIC_X_INDEX], values[SMOOTH_CUBIC_Y_INDEX], relative)
    path.cubicTo(control1X, control1Y, x2, y2, x, y)
    recordCubicControl(x2, y2)
    moveCursorTo(x, y)
    command = SMOOTH_CUBIC_COMMAND
}

internal const val SMOOTH_CUBIC_COMMAND = 'S'
internal const val SMOOTH_CUBIC_X2_INDEX = 0
internal const val SMOOTH_CUBIC_Y2_INDEX = 1
internal const val SMOOTH_CUBIC_X_INDEX = 2
internal const val SMOOTH_CUBIC_Y_INDEX = 3

internal fun PathDataParser.reflectedCubicControl(): Pair<Float, Float> {
    val previous = command.uppercaseChar()
    if (previous != CUBIC_SMOOTH_PREVIOUS && previous != CUBIC_SMOOTH_CONTINUATION) {
        return cursorPosition()
    }
    val (cursorX, cursorY) = cursorPosition()
    val (controlX, controlY) = cubicControl()
    return cursorX * PAIR_SIZE - controlX to cursorY * PAIR_SIZE - controlY
}

internal fun PathDataParser.applyQuadratic(relative: Boolean) {
    var keepGoing = true
    while (keepGoing && peekIsNumberStart()) {
        val values = readNumbers(QUADRATIC_POINTS)
        if (values == null) {
            keepGoing = false
        } else {
            applyQuadraticValues(values, relative)
        }
    }
}

internal fun PathDataParser.applyQuadraticValues(values: List<Float>, relative: Boolean) {
    val (controlX, controlY) = absolutePair(values[QUAD_CX_INDEX], values[QUAD_CY_INDEX], relative)
    val (x, y) = absolutePair(values[QUAD_X_INDEX], values[QUAD_Y_INDEX], relative)
    path.quadTo(controlX, controlY, x, y)
    recordQuadControl(controlX, controlY)
    moveCursorTo(x, y)
    command = QUADRATIC_COMMAND
}

internal const val QUADRATIC_COMMAND = 'Q'
internal const val QUAD_CX_INDEX = 0
internal const val QUAD_CY_INDEX = 1
internal const val QUAD_X_INDEX = 2
internal const val QUAD_Y_INDEX = 3

internal fun PathDataParser.applySmoothQuadratic(relative: Boolean) {
    var keepGoing = true
    while (keepGoing && peekIsNumberStart()) {
        val point = readPoint()
        if (point == null) {
            keepGoing = false
        } else {
            applySmoothQuadPoint(point, relative)
        }
    }
}

internal fun PathDataParser.applySmoothQuadPoint(point: Pair<Float, Float>, relative: Boolean) {
    val (x, y) = absolutePair(point.first, point.second, relative)
    val (controlX, controlY) = reflectedQuadControl()
    path.quadTo(controlX, controlY, x, y)
    recordQuadControl(controlX, controlY)
    moveCursorTo(x, y)
    command = SMOOTH_QUAD_COMMAND
}

internal const val SMOOTH_QUAD_COMMAND = 'T'

internal fun PathDataParser.reflectedQuadControl(): Pair<Float, Float> {
    val previous = command.uppercaseChar()
    if (previous != QUAD_SMOOTH_PREVIOUS && previous != QUAD_SMOOTH_CONTINUATION) {
        return cursorPosition()
    }
    val (cursorX, cursorY) = cursorPosition()
    val (controlX, controlY) = quadControl()
    return cursorX * PAIR_SIZE - controlX to cursorY * PAIR_SIZE - controlY
}

internal fun PathDataParser.applyArc(relative: Boolean) {
    var keepGoing = true
    while (keepGoing && peekIsNumberStart()) {
        keepGoing = applySingleArc(relative)
    }
}

internal fun PathDataParser.applySingleArc(relative: Boolean): Boolean {
    val values = readNumbers(ARC_POINTS) ?: return false
    val largeArc = readArcFlag(values[ARC_LARGE_ARC_INDEX]) ?: return false
    val sweep = readArcFlag(values[ARC_SWEEP_INDEX]) ?: return false
    val (x, y) = absolutePair(values[ARC_END_X_INDEX], values[ARC_END_Y_INDEX], relative)
    val (cursorX, cursorY) = cursorPosition()
    arcTo(
        path,
        cursorX,
        cursorY,
        values[ARC_RADIUS_X_INDEX],
        values[ARC_RADIUS_Y_INDEX],
        values[ARC_ANGLE_INDEX],
        largeArc,
        sweep,
        x,
        y,
    )
    moveCursorTo(x, y)
    command = ARC_COMMAND
    return true
}

internal const val ARC_COMMAND = 'A'
internal const val ARC_RADIUS_X_INDEX = 0
internal const val ARC_RADIUS_Y_INDEX = 1
internal const val ARC_ANGLE_INDEX = 2
internal const val ARC_LARGE_ARC_INDEX = 3
internal const val ARC_SWEEP_INDEX = 4
internal const val ARC_END_X_INDEX = 5
internal const val ARC_END_Y_INDEX = 6

internal fun readArcFlag(value: Float): Int? {
    if (value == FLAG_ZERO_VALUE) return ARC_FLAG_ZERO
    if (value == FLAG_ONE_VALUE) return ARC_FLAG_ONE
    return null
}

internal const val FLAG_ZERO_VALUE = 0f
internal const val FLAG_ONE_VALUE = 1f
internal const val ARC_FLAG_ZERO = 0
internal const val ARC_FLAG_ONE = 1

internal fun PathDataParser.applyClose() {
    path.close()
    val (x, y) = subpathStart()
    moveCursorTo(x, y)
    command = 'Z'
}

internal fun parsePathData(data: String): Path = PathDataParser(data).parse()
