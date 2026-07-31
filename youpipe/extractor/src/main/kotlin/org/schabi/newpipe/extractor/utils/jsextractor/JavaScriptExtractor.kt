package org.schabi.newpipe.extractor.utils.jsextractor

import org.schabi.newpipe.extractor.exceptions.ParsingException
import javax.annotation.Nonnull

/**
 * Utility class for extracting functions from JavaScript code.
 */
object JavaScriptExtractor {

    /**
     * Searches the given JavaScript code for the identifier of a function
     * and returns its body.
     *
     * @param jsCode JavaScript code
     * @param start start of the function (without the opening brace)
     * @return extracted code (opening brace + function + closing brace)
     * @throws ParsingException
     */
    @Nonnull
    @JvmStatic
    @Throws(ParsingException::class)
    fun matchToClosingBrace(jsCode: String, start: String): String {
        var startIndex = jsCode.indexOf(start)
        if (startIndex < 0) throw ParsingException("Start not found")
        startIndex += start.length
        val js = jsCode.substring(startIndex)

        val lexer = Lexer(js)
        var visitedOpenBrace = false

        while (true) {
            val parsedToken = lexer.getNextToken()
            val t = parsedToken.token

            if (t == Token.LC) {
                visitedOpenBrace = true
            } else if (visitedOpenBrace && lexer.isBalanced()) {
                return js.substring(0, parsedToken.end)
            } else if (t == Token.EOF) {
                throw ParsingException("Could not find matching braces")
            }
        }
    }
}
