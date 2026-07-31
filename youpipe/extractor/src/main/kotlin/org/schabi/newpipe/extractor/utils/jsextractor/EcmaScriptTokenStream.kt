/*
 * Source: Mozilla Rhino, org.mozilla.javascript.TokenStream
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 */
package org.schabi.newpipe.extractor.utils.jsextractor

import org.mozilla.javascript.Kit
import org.mozilla.javascript.ScriptRuntime
import org.schabi.newpipe.extractor.exceptions.ParsingException

/**
 * Based on Mozilla Rhino's (v1.7.14) org.mozilla.javascript.TokenStream
 *
 * Changes:
 * - Tailored for [Lexer]
 * - Removed all not needed code to improve performance
 * - Optimized for ECMAScript6/2015
 */
internal class EcmaScriptTokenStream(
    private val sourceString: String,
    var lineno: Int,
    private val strictMode: Boolean
) {
    companion object {
        private const val EOF_CHAR = -1
        private const val REPORT_NUMBER_FORMAT_ERROR = -2
        private const val BYTE_ORDER_MARK = '\uFEFF'
        private const val NUMERIC_SEPARATOR = '_'

        @JvmStatic
        private fun stringToKeywordForES(name: String, isStrict: Boolean): Token {
            return when (name) {
                "break" -> Token.BREAK
                "case" -> Token.CASE
                "catch" -> Token.CATCH
                "const" -> Token.CONST
                "continue" -> Token.CONTINUE
                "debugger" -> Token.DEBUGGER
                "default" -> Token.DEFAULT
                "delete" -> Token.DELPROP
                "do" -> Token.DO
                "else" -> Token.ELSE
                "export" -> Token.EXPORT
                "finally" -> Token.FINALLY
                "for" -> Token.FOR
                "function" -> Token.FUNCTION
                "if" -> Token.IF
                "import" -> Token.IMPORT
                "in" -> Token.IN
                "instanceof" -> Token.INSTANCEOF
                "new" -> Token.NEW
                "return" -> Token.RETURN
                "switch" -> Token.SWITCH
                "this" -> Token.THIS
                "throw" -> Token.THROW
                "try" -> Token.TRY
                "typeof" -> Token.TYPEOF
                "var" -> Token.VAR
                "void" -> Token.VOID
                "while" -> Token.WHILE
                "with" -> Token.WITH
                "yield" -> Token.YIELD
                "false" -> Token.FALSE
                "null" -> Token.NULL
                "true" -> Token.TRUE
                "let" -> Token.LET
                "class", "extends", "super", "await", "enum" -> Token.RESERVED
                "implements", "interface", "package", "private", "protected", "public", "static" ->
                    if (isStrict) Token.RESERVED else Token.EOF
                else -> Token.EOF
            }
        }

        private fun isAlpha(c: Int): Boolean {
            if (c <= 'Z'.code) return 'A'.code <= c
            return 'a'.code <= c && c <= 'z'.code
        }

        private fun isDigit(base: Int, c: Int): Boolean = when (base) {
            10 -> isDigit(c)
            16 -> isHexDigit(c)
            8 -> isOctalDigit(c)
            2 -> isDualDigit(c)
            else -> false
        }

        private fun isDualDigit(c: Int): Boolean = c == '0'.code || c == '1'.code
        private fun isOctalDigit(c: Int): Boolean = '0'.code <= c && c <= '7'.code
        private fun isDigit(c: Int): Boolean = '0'.code <= c && c <= '9'.code
        private fun isHexDigit(c: Int): Boolean =
            ('0'.code <= c && c <= '9'.code) || ('a'.code <= c && c <= 'f'.code) || ('A'.code <= c && c <= 'F'.code)

        private fun isJSSpace(c: Int): Boolean {
            if (c <= 127) return c == 0x20 || c == 0x9 || c == 0xC || c == 0xB
            return c == 0xA0 || c == BYTE_ORDER_MARK.code || Character.getType(c.toChar()) == Character.SPACE_SEPARATOR.toInt()
        }

        private fun isJSFormatChar(c: Int): Boolean =
            c > 127 && Character.getType(c.toChar()) == Character.FORMAT.toInt()
    }

    // Record start and end positions of last scanned token.
    @JvmField var tokenBeg: Int = 0
    @JvmField var tokenEnd: Int = 0

    private var dirtyLine: Boolean = false
    private var stringBuffer: CharArray = CharArray(128)
    private var stringBufferTop: Int = 0
    private val ungetBuffer: IntArray = IntArray(3)
    private var ungetCursor: Int = 0
    private var lineEndChar: Int = -1
    private var sourceCursor: Int = 0
    private var cursor: Int = 0

    private fun stringToKeyword(name: String): Token = stringToKeywordForES(name, strictMode)

    @Throws(ParsingException::class)
    fun getToken(): Token {
        var c: Int

        while (true) {
            // Eat whitespace, possibly sensitive to newlines.
            while (true) {
                c = getChar()
                if (c == EOF_CHAR) {
                    tokenBeg = cursor - 1
                    tokenEnd = cursor
                    return Token.EOF
                } else if (c == '\n'.code) {
                    dirtyLine = false
                    tokenBeg = cursor - 1
                    tokenEnd = cursor
                    return Token.EOL
                } else if (!isJSSpace(c)) {
                    if (c != '-'.code) dirtyLine = true
                    break
                }
            }

            tokenBeg = cursor - 1
            tokenEnd = cursor

            var identifierStart: Boolean
            var isUnicodeEscapeStart = false
            if (c == '\\'.code) {
                c = getChar()
                if (c == 'u'.code) {
                    identifierStart = true
                    isUnicodeEscapeStart = true
                    stringBufferTop = 0
                } else {
                    identifierStart = false
                    ungetChar(c)
                    c = '\\'.code
                }
            } else {
                identifierStart = Character.isJavaIdentifierStart(c.toChar())
                if (identifierStart) {
                    stringBufferTop = 0
                    addToString(c)
                }
            }

            if (identifierStart) {
                var containsEscape = isUnicodeEscapeStart
                while (true) {
                    if (isUnicodeEscapeStart) {
                        var escapeVal = 0
                        for (i in 0 until 4) {
                            c = getChar()
                            escapeVal = Kit.xDigitToInt(c, escapeVal)
                            if (escapeVal < 0) break
                        }
                        if (escapeVal < 0) throw ParsingException("invalid unicode escape")
                        addToString(escapeVal)
                        isUnicodeEscapeStart = false
                    } else {
                        c = getChar()
                        if (c == '\\'.code) {
                            c = getChar()
                            if (c == 'u'.code) {
                                isUnicodeEscapeStart = true
                                containsEscape = true
                            } else {
                                throw ParsingException("illegal character: '${c.toChar()}'")
                            }
                        } else {
                            if (c == EOF_CHAR || c == BYTE_ORDER_MARK.code || !Character.isJavaIdentifierPart(c.toChar())) break
                            addToString(c)
                        }
                    }
                }
                ungetChar(c)

                val str = getStringFromBuffer()
                if (!containsEscape) {
                    val result = stringToKeyword(str)
                    if (result != Token.EOF) return result
                }
                return Token.NAME
            }

            // is it a number?
            if (isDigit(c) || (c == '.'.code && isDigit(peekChar()))) {
                stringBufferTop = 0
                var base = 10
                var isOldOctal = false

                if (c == '0'.code) {
                    c = getChar()
                    if (c == 'x'.code || c == 'X'.code) {
                        base = 16; c = getChar()
                    } else if (c == 'o'.code || c == 'O'.code) {
                        base = 8; c = getChar()
                    } else if (c == 'b'.code || c == 'B'.code) {
                        base = 2; c = getChar()
                    } else if (isDigit(c)) {
                        base = 8; isOldOctal = true
                    } else {
                        addToString('0'.code)
                    }
                }

                val emptyDetector = stringBufferTop
                if (base == 10 || base == 16 || (base == 8 && !isOldOctal) || base == 2) {
                    c = readDigits(base, c)
                    if (c == REPORT_NUMBER_FORMAT_ERROR) throw ParsingException("number format error")
                } else {
                    while (isDigit(c)) {
                        if (c >= '8'.code) {
                            base = 10
                            c = readDigits(base, c)
                            if (c == REPORT_NUMBER_FORMAT_ERROR) throw ParsingException("number format error")
                            break
                        }
                        addToString(c)
                        c = getChar()
                    }
                }
                if (stringBufferTop == emptyDetector && base != 10) throw ParsingException("number format error")

                if (c == 'n'.code) {
                    c = getChar()
                } else if (base == 10 && (c == '.'.code || c == 'e'.code || c == 'E'.code)) {
                    if (c == '.'.code) {
                        addToString(c); c = getChar()
                        c = readDigits(base, c)
                        if (c == REPORT_NUMBER_FORMAT_ERROR) throw ParsingException("number format error")
                    }
                    if (c == 'e'.code || c == 'E'.code) {
                        addToString(c); c = getChar()
                        if (c == '+'.code || c == '-'.code) { addToString(c); c = getChar() }
                        if (!isDigit(c)) throw ParsingException("missing exponent")
                        c = readDigits(base, c)
                        if (c == REPORT_NUMBER_FORMAT_ERROR) throw ParsingException("number format error")
                    }
                }
                ungetChar(c)
                tokenEnd = cursor
                return Token.NUMBER
            }

            // string or template literal?
            if (c == '"'.code || c == '\''.code || c == '`'.code) {
                val quoteChar = c
                stringBufferTop = 0
                c = getCharIgnoreLineEnd(false)

                strLoop@ while (c != quoteChar) {
                    var unterminated = false
                    if (c == EOF_CHAR) {
                        unterminated = true
                    } else if (c == '\n'.code) {
                        when (lineEndChar) {
                            '\n'.code, '\r'.code -> unterminated = true
                            0x2028, 0x2029 -> c = lineEndChar
                        }
                    }
                    if (unterminated) throw ParsingException("unterminated string literal")

                    if (c == '\\'.code) {
                        var escapeVal: Int
                        c = getChar()
                        when (c) {
                            'b'.code -> c = '\b'.code
                            'f'.code -> c = '\u000C'.code
                            'n'.code -> c = '\n'.code
                            'r'.code -> c = '\r'.code
                            't'.code -> c = '\t'.code
                            'v'.code -> c = 0xb
                            'u'.code -> {
                                val escapeStart = stringBufferTop
                                addToString('u'.code)
                                escapeVal = 0
                                for (i in 0 until 4) {
                                    c = getChar()
                                    escapeVal = Kit.xDigitToInt(c, escapeVal)
                                    if (escapeVal < 0) continue@strLoop
                                    addToString(c)
                                }
                                stringBufferTop = escapeStart
                                c = escapeVal
                            }
                            'x'.code -> {
                                c = getChar()
                                escapeVal = Kit.xDigitToInt(c, 0)
                                if (escapeVal < 0) { addToString('x'.code); continue }
                                val c1 = c
                                c = getChar()
                                escapeVal = Kit.xDigitToInt(c, escapeVal)
                                if (escapeVal < 0) { addToString('x'.code); addToString(c1); continue }
                                c = escapeVal
                            }
                            '\n'.code -> { c = getChar(); continue }
                            else -> {
                                if ('0'.code <= c && c < '8'.code) {
                                    var v = c - '0'.code
                                    c = getChar()
                                    if ('0'.code <= c && c < '8'.code) {
                                        v = 8 * v + c - '0'.code
                                        c = getChar()
                                        if ('0'.code <= c && c < '8'.code && v <= 0x1f) {
                                            v = 8 * v + c - '0'.code
                                            c = getChar()
                                        }
                                    }
                                    ungetChar(c)
                                    c = v
                                }
                            }
                        }
                    }
                    addToString(c)
                    c = getChar(false)
                }

                tokenEnd = cursor
                return if (quoteChar == '`'.code) Token.TEMPLATE_LITERAL else Token.STRING
            }

            when (c) {
                ';'.code -> return Token.SEMI
                '['.code -> return Token.LB
                ']'.code -> return Token.RB
                '{'.code -> return Token.LC
                '}'.code -> return Token.RC
                '('.code -> return Token.LP
                ')'.code -> return Token.RP
                ','.code -> return Token.COMMA
                '?'.code -> return Token.HOOK
                ':'.code -> return Token.COLON
                '.'.code -> return Token.DOT
                '|'.code -> {
                    if (matchChar('|'.code)) return Token.OR
                    else if (matchChar('='.code)) return Token.ASSIGN_BITOR
                    else return Token.BITOR
                }
                '^'.code -> {
                    if (matchChar('='.code)) return Token.ASSIGN_BITXOR
                    return Token.BITXOR
                }
                '&'.code -> {
                    if (matchChar('&'.code)) return Token.AND
                    else if (matchChar('='.code)) return Token.ASSIGN_BITAND
                    else return Token.BITAND
                }
                '='.code -> {
                    if (matchChar('='.code)) {
                        if (matchChar('='.code)) return Token.SHEQ
                        return Token.EQ
                    } else if (matchChar('>'.code)) return Token.ARROW
                    else return Token.ASSIGN
                }
                '!'.code -> {
                    if (matchChar('='.code)) {
                        if (matchChar('='.code)) return Token.SHNE
                        return Token.NE
                    }
                    return Token.NOT
                }
                '<'.code -> {
                    if (matchChar('!'.code)) {
                        if (matchChar('-'.code)) {
                            if (matchChar('-'.code)) {
                                tokenBeg = cursor - 4
                                skipLine()
                                return Token.COMMENT
                            }
                            ungetCharIgnoreLineEnd('-'.code)
                        }
                        ungetCharIgnoreLineEnd('!'.code)
                    }
                    if (matchChar('<'.code)) {
                        if (matchChar('='.code)) return Token.ASSIGN_LSH
                        return Token.LSH
                    }
                    if (matchChar('='.code)) return Token.LE
                    return Token.LT
                }
                '>'.code -> {
                    if (matchChar('>'.code)) {
                        if (matchChar('>'.code)) {
                            if (matchChar('='.code)) return Token.ASSIGN_URSH
                            return Token.URSH
                        }
                        if (matchChar('='.code)) return Token.ASSIGN_RSH
                        return Token.RSH
                    }
                    if (matchChar('='.code)) return Token.GE
                    return Token.GT
                }
                '*'.code -> {
                    if (matchChar('*'.code)) {
                        if (matchChar('='.code)) return Token.ASSIGN_EXP
                        return Token.EXP
                    }
                    if (matchChar('='.code)) return Token.ASSIGN_MUL
                    return Token.MUL
                }
                '/'.code -> {
                    if (matchChar('/'.code)) {
                        tokenBeg = cursor - 2
                        skipLine()
                        return Token.COMMENT
                    }
                    if (matchChar('*'.code)) {
                        var lookForSlash = false
                        tokenBeg = cursor - 2
                        if (matchChar('*'.code)) lookForSlash = true
                        while (true) {
                            c = getChar()
                            if (c == EOF_CHAR) {
                                tokenEnd = cursor - 1
                                throw ParsingException("unterminated comment")
                            } else if (c == '*'.code) {
                                lookForSlash = true
                            } else if (c == '/'.code) {
                                if (lookForSlash) { tokenEnd = cursor; return Token.COMMENT }
                            } else {
                                lookForSlash = false
                                tokenEnd = cursor
                            }
                        }
                    }
                    if (matchChar('='.code)) return Token.ASSIGN_DIV
                    return Token.DIV
                }
                '%'.code -> {
                    if (matchChar('='.code)) return Token.ASSIGN_MOD
                    return Token.MOD
                }
                '~'.code -> return Token.BITNOT
                '+'.code -> {
                    if (matchChar('='.code)) return Token.ASSIGN_ADD
                    else if (matchChar('+'.code)) return Token.INC
                    else return Token.ADD
                }
                '-'.code -> {
                    var t = Token.SUB
                    if (matchChar('='.code)) t = Token.ASSIGN_SUB
                    else if (matchChar('-'.code)) {
                        if (!dirtyLine && matchChar('>'.code)) {
                            skipLine()
                            return Token.COMMENT
                        }
                        t = Token.DEC
                    }
                    dirtyLine = true
                    return t
                }
                else -> throw ParsingException("illegal character: '${c.toChar()}'")
            }
        }
    }

    private fun readDigits(base: Int, firstC: Int): Int {
        if (isDigit(base, firstC)) {
            addToString(firstC)
            var c = getChar()
            if (c == EOF_CHAR) return EOF_CHAR
            while (true) {
                if (c == NUMERIC_SEPARATOR.code) {
                    c = getChar()
                    if (c == '\n'.code || c == EOF_CHAR) return REPORT_NUMBER_FORMAT_ERROR
                    if (!isDigit(base, c)) { ungetChar(c); return NUMERIC_SEPARATOR.code }
                    addToString(NUMERIC_SEPARATOR.code)
                } else if (isDigit(base, c)) {
                    addToString(c)
                    c = getChar()
                    if (c == EOF_CHAR) return EOF_CHAR
                } else return c
            }
        }
        return firstC
    }

    fun readRegExp(startToken: Token) {
        val start = tokenBeg
        stringBufferTop = 0
        if (startToken == Token.ASSIGN_DIV) {
            addToString('='.code)
        } else {
            if (startToken != Token.DIV) Kit.codeBug()
            if (peekChar() == '*'.code) {
                tokenEnd = cursor - 1
                throw ParsingException("msg.unterminated.re.lit")
            }
        }

        var inCharSet = false
        var c: Int
        while (getChar().also { c = it } != '/'.code || inCharSet) {
            if (c == '\n'.code || c == EOF_CHAR) throw ParsingException("msg.unterminated.re.lit")
            if (c == '\\'.code) {
                addToString(c)
                c = getChar()
                if (c == '\n'.code || c == EOF_CHAR) throw ParsingException("msg.unterminated.re.lit")
            } else if (c == '['.code) inCharSet = true
            else if (c == ']'.code) inCharSet = false
            addToString(c)
        }

        while (true) {
            c = getCharIgnoreLineEnd()
            if ("gimysu".indexOf(c.toChar()) != -1) addToString(c)
            else if (isAlpha(c)) throw ParsingException("msg.invalid.re.flag")
            else { ungetCharIgnoreLineEnd(c); break }
        }
        tokenEnd = start + stringBufferTop + 2
    }

    private fun getStringFromBuffer(): String {
        tokenEnd = cursor
        return String(stringBuffer, 0, stringBufferTop)
    }

    private fun addToString(c: Int) {
        val n = stringBufferTop
        if (n == stringBuffer.size) {
            val tmp = CharArray(stringBuffer.size * 2)
            System.arraycopy(stringBuffer, 0, tmp, 0, n)
            stringBuffer = tmp
        }
        stringBuffer[n] = c.toChar()
        stringBufferTop = n + 1
    }

    private fun ungetChar(c: Int) {
        if (ungetCursor != 0 && ungetBuffer[ungetCursor - 1] == '\n'.code) Kit.codeBug()
        ungetBuffer[ungetCursor++] = c
        cursor--
    }

    private fun matchChar(test: Int): Boolean {
        val c = getCharIgnoreLineEnd()
        if (c == test) { tokenEnd = cursor; return true }
        ungetCharIgnoreLineEnd(c)
        return false
    }

    private fun peekChar(): Int {
        val c = getChar()
        ungetChar(c)
        return c
    }

    private fun getChar(): Int = getChar(true, false)
    private fun getChar(skipFormattingChars: Boolean): Int = getChar(skipFormattingChars, false)

    private fun getChar(skipFormattingChars: Boolean, ignoreLineEnd: Boolean): Int {
        if (ungetCursor != 0) { cursor++; return ungetBuffer[--ungetCursor] }
        while (true) {
            if (sourceCursor == sourceString.length) return EOF_CHAR
            cursor++
            var c = sourceString[sourceCursor++].code

            if (!ignoreLineEnd && lineEndChar >= 0) {
                if (lineEndChar == '\r'.code && c == '\n'.code) { lineEndChar = '\n'.code; continue }
                lineEndChar = -1
                lineno++
            }

            if (c <= 127) {
                if (c == '\n'.code || c == '\r'.code) { lineEndChar = c; c = '\n'.code }
            } else {
                if (c == BYTE_ORDER_MARK.code) return c
                if (skipFormattingChars && isJSFormatChar(c)) continue
                if (ScriptRuntime.isJSLineTerminator(c)) { lineEndChar = c; c = '\n'.code }
            }
            return c
        }
    }

    private fun getCharIgnoreLineEnd(): Int = getChar(true, true)
    private fun getCharIgnoreLineEnd(skipFormattingChars: Boolean): Int = getChar(skipFormattingChars, true)

    private fun ungetCharIgnoreLineEnd(c: Int) {
        ungetBuffer[ungetCursor++] = c
        cursor--
    }

    private fun skipLine() {
        var c: Int
        while (getChar().also { c = it } != EOF_CHAR && c != '\n'.code) { }
        ungetChar(c)
        tokenEnd = cursor
    }

    @Throws(ParsingException::class)
    fun nextToken(): Token {
        var tt = getToken()
        while (tt == Token.EOL || tt == Token.COMMENT) tt = getToken()
        return tt
    }
}
