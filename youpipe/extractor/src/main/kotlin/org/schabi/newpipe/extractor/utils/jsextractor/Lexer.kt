package org.schabi.newpipe.extractor.utils.jsextractor

import org.schabi.newpipe.extractor.exceptions.ParsingException
import java.util.Stack

/**
 * JavaScript lexer that is able to parse JavaScript code and return its tokens.
 *
 * The algorithm for distinguishing between division operators and regex literals
 * was taken from the RESS lexer.
 */
class Lexer(js: String) {

    private class Paren(val funcExpr: Boolean, val conditional: Boolean)

    private class Brace(val isBlock: Boolean, val paren: Paren?)

    private open class MetaToken(val token: Token, val lineno: Int)

    private class BraceMetaToken(token: Token, lineno: Int, val brace: Brace) : MetaToken(token, lineno)

    private class ParenMetaToken(token: Token, lineno: Int, val paren: Paren) : MetaToken(token, lineno)

    private class LookBehind {
        private val list: Array<MetaToken?> = arrayOfNulls(3)

        fun push(t: MetaToken) {
            var toShift: MetaToken? = t
            for (i in 0..2) {
                val tmp = list[i]
                list[i] = toShift
                toShift = tmp
            }
        }

        fun one(): MetaToken? = list[0]
        fun two(): MetaToken? = list[1]
        fun three(): MetaToken? = list[2]

        fun oneIs(token: Token): Boolean = list[0]?.token == token
        fun twoIs(token: Token): Boolean = list[1]?.token == token
        fun threeIs(token: Token): Boolean = list[2]?.token == token
    }

    /**
     * Parsed token, containing the token and its position in the input string
     */
    class ParsedToken(val token: Token, val start: Int, val end: Int)

    private val stream: EcmaScriptTokenStream = EcmaScriptTokenStream(js, 0, false)
    private val lastThree: LookBehind = LookBehind()
    private val braceStack: Stack<Brace> = Stack()
    private val parenStack: Stack<Paren> = Stack()

    /**
     * Continue parsing and return the next token
     * @return next token
     * @throws ParsingException
     */
    @Throws(ParsingException::class)
    fun getNextToken(): ParsedToken {
        var token = stream.nextToken()

        if ((token == Token.DIV || token == Token.ASSIGN_DIV) && isRegexStart()) {
            stream.readRegExp(token)
            token = Token.REGEXP
        }

        val parsedToken = ParsedToken(token, stream.tokenBeg, stream.tokenEnd)
        keepBooks(parsedToken)
        return parsedToken
    }

    /**
     * Check if the parser is balanced (equal amount of open and closed parentheses and braces)
     * @return true if balanced
     */
    fun isBalanced(): Boolean = braceStack.isEmpty() && parenStack.isEmpty()

    /**
     * Evaluate the token for possible regex start and handle updating the
     * `self.last_three`, `self.paren_stack` and `self.brace_stack`
     */
    @Throws(ParsingException::class)
    internal fun keepBooks(parsedToken: ParsedToken) {
        if (parsedToken.token.isPunct) {
            when (parsedToken.token) {
                Token.LP -> { handleOpenParenBooks(); return }
                Token.LC -> { handleOpenBraceBooks(); return }
                Token.RP -> { handleCloseParenBooks(parsedToken.start); return }
                Token.RC -> { handleCloseBraceBooks(parsedToken.start); return }
                else -> {}
            }
        }
        if (parsedToken.token != Token.COMMENT) {
            lastThree.push(MetaToken(parsedToken.token, stream.lineno))
        }
    }

    /**
     * Handle book keeping when we find an `(`
     */
    internal fun handleOpenParenBooks() {
        var funcExpr = false
        if (lastThree.oneIs(Token.FUNCTION)) {
            funcExpr = lastThree.two() != null && checkForExpression(lastThree.two()!!.token)
        } else if (lastThree.twoIs(Token.FUNCTION)) {
            funcExpr = lastThree.three() != null && checkForExpression(lastThree.three()!!.token)
        }

        val conditional = lastThree.one() != null && lastThree.one()!!.token.isConditional()
        val paren = Paren(funcExpr, conditional)
        parenStack.push(paren)
        lastThree.push(ParenMetaToken(Token.LP, stream.lineno, paren))
    }

    /**
     * Handle book keeping when we find an `{`
     */
    internal fun handleOpenBraceBooks() {
        var isBlock = true
        if (lastThree.one() != null) {
            isBlock = when (lastThree.one()!!.token) {
                Token.LP, Token.LC, Token.CASE -> false
                Token.COLON -> braceStack.isNotEmpty() && braceStack.lastElement().isBlock
                Token.RETURN, Token.YIELD, Token.YIELD_STAR -> lastThree.two()?.lineno != stream.lineno
                else -> !lastThree.one()!!.token.isOp
            }
        }

        var paren: Paren? = null
        if (lastThree.one() is ParenMetaToken && lastThree.one()!!.token == Token.RP) {
            paren = (lastThree.one() as ParenMetaToken).paren
        }
        val brace = Brace(isBlock, paren)
        braceStack.push(brace)
        lastThree.push(BraceMetaToken(Token.LC, stream.lineno, brace))
    }

    /**
     * Handle book keeping when we find an `)`
     */
    @Throws(ParsingException::class)
    internal fun handleCloseParenBooks(start: Int) {
        if (parenStack.isEmpty()) throw ParsingException("unmatched closing paren at $start")
        lastThree.push(ParenMetaToken(Token.RP, stream.lineno, parenStack.pop()))
    }

    /**
     * Handle book keeping when we find an `}`
     */
    @Throws(ParsingException::class)
    internal fun handleCloseBraceBooks(start: Int) {
        if (braceStack.isEmpty()) throw ParsingException("unmatched closing brace at $start")
        lastThree.push(BraceMetaToken(Token.RC, stream.lineno, braceStack.pop()))
    }

    internal fun checkForExpression(token: Token): Boolean =
        token.isOp || token == Token.RETURN || token == Token.CASE

    /**
     * Detect if the `/` is the beginning of a regex or is division
     */
    internal fun isRegexStart(): Boolean {
        if (lastThree.one() != null) {
            val t = lastThree.one()!!.token
            return when {
                t.isKeyw -> t != Token.THIS
                t == Token.RP && lastThree.one() is ParenMetaToken ->
                    (lastThree.one() as ParenMetaToken).paren.conditional
                t == Token.RC && lastThree.one() is BraceMetaToken -> {
                    val mt = lastThree.one() as BraceMetaToken
                    if (mt.brace.isBlock) {
                        if (mt.brace.paren != null) !mt.brace.paren.funcExpr else true
                    } else false
                }
                t.isPunct -> t != Token.RB
                else -> false
            }
        }
        return true
    }
}
