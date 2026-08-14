package com.vayunmathur.calculator.util

import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.acosh
import kotlin.math.asin
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/** Whether trigonometric functions interpret/return angles in degrees or radians. */
enum class AngleMode { RADIANS, DEGREES }

/** Thrown when an expression cannot be tokenised or parsed. */
class ExpressionError(message: String) : Exception(message)

/** Values supplied at evaluation time: the free variable, angle mode, and last answer. */
class EvalContext(val variable: Double, val angle: AngleMode, val ans: Double)

/**
 * A parsed, reusable mathematical expression. Parsing happens once (via [parse]); the
 * tree can be [eval]'d many times with different values of the free variable (`x` for
 * Cartesian graphs, `θ`/`t` for polar), which keeps graphing and analysis cheap.
 *
 * Supported: `+ - * / ^ %`, unary minus, postfix `!` (factorial, via the gamma
 * function), implicit multiplication (`2x`, `3(x+1)`, `2sin(x)`), `|x|` bars,
 * scientific notation (`1.5E3`), the constants `pi`/`π`/`e`/`tau`/`phi`, the free
 * variable (`x`/`t`/`θ`/`theta`), `ans` (previous answer), a large unary-function
 * library, and multi-argument functions: `log(b,x)`, `root(n,x)`, `nCr(n,r)`,
 * `nPr(n,r)`, `mod(a,b)`, `gcd(a,b)`, `lcm(a,b)`, `atan2(y,x)`, `max(…)`, `min(…)`.
 */
class Expression private constructor(private val root: Node) {

    /**
     * Evaluate for a given free-variable value, [angle] mode and [ans], returning the numeric
     * magnitude only. Preserves the graphing path and every dimensionless caller: for a
     * dimensionless expression this is exactly the number, so nothing downstream changes.
     */
    fun eval(variable: Double = 0.0, angle: AngleMode = AngleMode.RADIANS, ans: Double = 0.0): Double =
        evalQuantity(variable, angle, ans).value

    /** Evaluate to a full [Quantity], carrying units through the calculation. */
    fun evalQuantity(variable: Double = 0.0, angle: AngleMode = AngleMode.RADIANS, ans: Double = 0.0): Quantity =
        root.eval(EvalContext(variable, angle, ans))

    companion object {
        fun parse(input: String): Expression = Expression(Parser(input).parse())
    }

    // ---- AST ----

    internal sealed interface Node {
        fun eval(ctx: EvalContext): Quantity
    }

    private class Num(val value: Double) : Node {
        override fun eval(ctx: EvalContext) = Quantity.scalar(value)
    }

    private object Var : Node {
        override fun eval(ctx: EvalContext) = Quantity.scalar(ctx.variable)
    }

    private object Ans : Node {
        override fun eval(ctx: EvalContext) = Quantity.scalar(ctx.ans)
    }

    private class UnitNode(val unit: UnitDef) : Node {
        override fun eval(ctx: EvalContext) = Quantity(unit.factorToBase, unit.dimension, unit.offsetK)
    }

    /** An absolute point in time, written `#<epochSeconds>` (inserted by the date/time pickers). */
    private class InstantLit(val epochSeconds: Double) : Node {
        override fun eval(ctx: EvalContext) = Quantity(epochSeconds, Dimension.TIME, instant = true)
    }

    private class Neg(val operand: Node) : Node {
        override fun eval(ctx: EvalContext) = -operand.eval(ctx)
    }

    private class Fact(val operand: Node) : Node {
        override fun eval(ctx: EvalContext) = Quantity.scalar(factorial(operand.eval(ctx).requireScalar("Factorial")))
    }

    private class Binary(val op: Char, val left: Node, val right: Node) : Node {
        override fun eval(ctx: EvalContext): Quantity {
            val a = left.eval(ctx)
            val b = right.eval(ctx)
            return when (op) {
                '+' -> a + b
                '-' -> a - b
                '*' -> a * b
                '/' -> a / b
                '%' -> a % b
                '^' -> a.pow(b)
                else -> throw ExpressionError("Unknown operator '$op'")
            }
        }
    }

    private class Call(val name: String, val args: List<Node>) : Node {
        override fun eval(ctx: EvalContext): Quantity {
            // `abs` is the one function that keeps its argument's dimension; everything else is
            // defined on plain numbers, so its arguments must be dimensionless.
            if (name == "abs") {
                if (args.size != 1) throw ExpressionError("abs expects 1 argument")
                return args[0].eval(ctx).abs()
            }
            val a = args.map { it.eval(ctx).requireScalar(name) }
            fun one(): Double {
                if (a.size != 1) throw ExpressionError("$name expects 1 argument")
                return a[0]
            }
            fun toRad(v: Double) = if (ctx.angle == AngleMode.DEGREES) v * PI / 180.0 else v
            fun fromRad(v: Double) = if (ctx.angle == AngleMode.DEGREES) v * 180.0 / PI else v
            val result: Double = when (name) {
                "sin" -> sin(toRad(one()))
                "cos" -> cos(toRad(one()))
                "tan" -> tan(toRad(one()))
                "asin" -> fromRad(asin(one()))
                "acos" -> fromRad(acos(one()))
                "atan" -> fromRad(atan(one()))
                "sec" -> 1.0 / cos(toRad(one()))
                "csc" -> 1.0 / sin(toRad(one()))
                "cot" -> 1.0 / tan(toRad(one()))
                "sinh" -> sinh(one())
                "cosh" -> cosh(one())
                "tanh" -> tanh(one())
                "asinh" -> asinh(one())
                "acosh" -> acosh(one())
                "atanh" -> atanh(one())
                "sqrt", "√" -> sqrt(one())
                "cbrt" -> cbrt(one())
                "exp" -> exp(one())
                "ln" -> ln(one())
                "log2" -> log2(one())
                "log" -> if (a.size == 2) ln(a[1]) / ln(a[0]) else log10(one())
                "floor" -> floor(one())
                "ceil" -> ceil(one())
                "round" -> round(one())
                "sign" -> sign(one())
                "gamma" -> gamma(one())
                "fact" -> factorial(one())
                "root" -> { require2(a); a[1].pow(1.0 / a[0]) }
                "mod" -> { require2(a); a[0] % a[1] }
                "atan2" -> { require2(a); fromRad(atan2(a[0], a[1])) }
                "ncr" -> { require2(a); combinations(a[0], a[1]) }
                "npr" -> { require2(a); permutations(a[0], a[1]) }
                "gcd" -> { require2(a); gcd(a[0], a[1]) }
                "lcm" -> { require2(a); lcm(a[0], a[1]) }
                "max" -> a.max()
                "min" -> a.min()
                else -> throw ExpressionError("Unknown function '$name'")
            }
            return Quantity.scalar(result)
        }

        private fun require2(a: List<Double>) {
            if (a.size != 2) throw ExpressionError("$name expects 2 arguments")
        }
    }

    /**
     * Recursive-descent parser. Precedence (low → high):
     * expr → term (('+'|'-') term)*
     * term → factor (('*'|'/'|'%'|implicit) factor)*
     * factor → ('+'|'-') factor | power
     * power → postfix ('^' factor)?          (right associative)
     * postfix → primary ('!')*                (factorial)
     * primary → number | const | var | ans | func '(' args ')' | '(' expr ')' | '|' expr '|'
     */
    private class Parser(input: String) {
        private val src = input
        private var pos = 0

        /** How many `|…|` pairs enclose the position being parsed. See [parseTerm]. */
        private var barDepth = 0

        fun parse(): Node {
            val node = parseExpr()
            skipSpaces()
            if (pos < src.length) throw ExpressionError("Unexpected '${src[pos]}'")
            return node
        }

        private fun skipSpaces() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun peek(): Char? {
            skipSpaces()
            return if (pos < src.length) src[pos] else null
        }

        private fun parseExpr(): Node {
            var left = parseTerm()
            while (true) {
                val c = peek() ?: break
                if (c == '+' || c == '-') {
                    pos++
                    left = Binary(c, left, parseTerm())
                } else break
            }
            return left
        }

        private fun parseTerm(): Node {
            var left = parseFactor()
            while (true) {
                val c = peek() ?: break
                when (c) {
                    '*', '/', '%' -> { pos++; left = Binary(c, left, parseFactor()) }
                    // Implicit multiplication: value directly followed by a group/name.
                    '(' -> left = Binary('*', left, parseFactor())
                    // '|' is ambiguous: it both opens and closes. Inside a pair of bars the
                    // next '|' is the closing one, so stop and let parsePrimary consume it.
                    // Treating it as the start of another factor made every |x| expression
                    // recurse into an unterminated bar and throw.
                    '|' -> if (barDepth > 0) break else left = Binary('*', left, parseFactor())
                    else -> if (c.isLetter() || c == '√' || c == 'π' || c == 'θ') {
                        left = Binary('*', left, parseFactor())
                    } else break
                }
            }
            return left
        }

        private fun parseFactor(): Node {
            val c = peek()
            if (c == '+') { pos++; return parseFactor() }
            if (c == '-') { pos++; return Neg(parseFactor()) }
            return parsePower()
        }

        private fun parsePower(): Node {
            val base = parsePostfix()
            if (peek() == '^') {
                pos++
                return Binary('^', base, parseFactor()) // right associative; -x^2 = -(x^2)
            }
            return base
        }

        private fun parsePostfix(): Node {
            var node = parsePrimary()
            while (peek() == '!') { pos++; node = Fact(node) }
            return node
        }

        private fun parsePrimary(): Node {
            val c = peek() ?: throw ExpressionError("Unexpected end of expression")
            when {
                c == '(' -> {
                    pos++
                    val inner = parseExpr()
                    if (peek() != ')') throw ExpressionError("Missing ')'")
                    pos++
                    return inner
                }
                c == '|' -> {
                    pos++
                    barDepth++
                    val inner = parseExpr()
                    if (peek() != '|') throw ExpressionError("Missing '|'")
                    pos++
                    barDepth--
                    return Call("abs", listOf(inner))
                }
                c.isDigit() || c == '.' -> return parseNumber()
                c == '#' -> return parseInstant()
                c.isLetter() || c == '√' || c == 'π' || c == 'θ' -> return parseIdentifier()
                else -> throw ExpressionError("Unexpected '$c'")
            }
        }

        /** Parse a `#<epochSeconds>` date literal (the `#` has been peeked, not consumed). */
        private fun parseInstant(): Node {
            pos++ // consume '#'
            val start = pos
            if (pos < src.length && src[pos] == '-') pos++
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            val text = src.substring(start, pos)
            val value = text.toDoubleOrNull() ?: throw ExpressionError("Invalid date literal '$text'")
            return InstantLit(value)
        }

        private fun parseNumber(): Node {
            skipSpaces()
            val start = pos
            var seenDot = false
            while (pos < src.length) {
                val ch = src[pos]
                if (ch.isDigit()) pos++
                else if (ch == '.' && !seenDot) { seenDot = true; pos++ }
                else break
            }
            // Scientific notation: uppercase 'E' only (lowercase 'e' is Euler's constant).
            if (pos < src.length && src[pos] == 'E') {
                val save = pos
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                if (pos < src.length && src[pos].isDigit()) {
                    while (pos < src.length && src[pos].isDigit()) pos++
                } else {
                    pos = save // not an exponent after all
                }
            }
            val text = src.substring(start, pos)
            val value = text.toDoubleOrNull() ?: throw ExpressionError("Invalid number '$text'")
            return Num(value)
        }

        private fun parseIdentifier(): Node {
            skipSpaces()
            if (src[pos] == '√') { pos++; return Call("sqrt", listOf(parseFactor())) }
            if (src[pos] == 'π') { pos++; return Num(PI) }
            if (src[pos] == 'θ') { pos++; return Var }
            val start = pos
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
            val raw = src.substring(start, pos)
            val name = raw.lowercase()
            return when (name) {
                "x", "t", "theta" -> Var
                "ans" -> Ans
                "pi" -> Num(PI)
                "tau" -> Num(2 * PI)
                "e" -> Num(E)
                "phi" -> Num((1 + sqrt(5.0)) / 2)
                else -> {
                    if (peek() == '(') {
                        pos++
                        val args = parseArgs()
                        if (peek() != ')') throw ExpressionError("Missing ')' after $name")
                        pos++
                        Call(name, args)
                    } else {
                        // Not a function call: try the unit registry (case-sensitively, so `mm`
                        // and `Mm` differ) before giving up.
                        val unit = UnitRegistry.parseTokens[raw]
                        if (unit != null) UnitNode(unit) else throw ExpressionError("Unknown symbol '$raw'")
                    }
                }
            }
        }

        private fun parseArgs(): List<Node> {
            val args = mutableListOf<Node>()
            if (peek() == ')') return args
            args.add(parseExpr())
            while (peek() == ',') { pos++; args.add(parseExpr()) }
            return args
        }
    }
}

// ---- Special functions shared by the AST ----

/** Unwrap a [Quantity] that must be dimensionless, or fail with a clear message. */
private fun Quantity.requireScalar(context: String): Double {
    if (!isDimensionless) throw ExpressionError("$context requires a dimensionless value")
    return value
}

/** Lanczos approximation of the gamma function (valid across the reals except poles). */
private fun gamma(x: Double): Double {
    val g = 7.0
    val c = doubleArrayOf(
        0.99999999999980993, 676.5203681218851, -1259.1392167224028,
        771.32342877765313, -176.61502916214059, 12.507343278686905,
        -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7,
    )
    if (x < 0.5) return PI / (sin(PI * x) * gamma(1 - x))
    val z = x - 1
    var a = c[0]
    val t = z + g + 0.5
    for (i in 1 until c.size) a += c[i] / (z + i)
    return sqrt(2 * PI) * t.pow(z + 0.5) * exp(-t) * a
}

/** Factorial via gamma so non-integers work too; exact for small non-negative integers. */
private fun factorial(n: Double): Double {
    if (n < 0 && n == floor(n)) return Double.NaN // poles at negative integers
    if (n == floor(n) && n <= 170) {
        var result = 1.0
        var i = 2
        while (i <= n.toInt()) { result *= i; i++ }
        return result
    }
    return gamma(n + 1)
}

private fun combinations(n: Double, r: Double): Double =
    factorial(n) / (factorial(r) * factorial(n - r))

private fun permutations(n: Double, r: Double): Double =
    factorial(n) / factorial(n - r)

private fun gcd(a: Double, b: Double): Double {
    var x = abs(round(a)).toLong()
    var y = abs(round(b)).toLong()
    while (y != 0L) { val t = y; y = x % y; x = t }
    return x.toDouble()
}

private fun lcm(a: Double, b: Double): Double {
    val g = gcd(a, b)
    return if (g == 0.0) 0.0 else abs(round(a) * round(b)) / g
}
