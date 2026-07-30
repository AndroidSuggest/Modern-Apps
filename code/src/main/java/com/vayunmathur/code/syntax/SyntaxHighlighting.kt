package com.vayunmathur.code.syntax

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.vayunmathur.library.ui.MaterialTheme

/**
 * Self-contained, regex-driven syntax highlighting.
 *
 * Each [Language] compiles once into a single alternation [Regex] whose top-level
 * alternatives are capturing groups (internally everything uses non-capturing groups so
 * group indices map 1:1 to [TokenKind]s). Highlighting is then a single left-to-right
 * `findAll` pass — because regex alternation is ordered and scanning resumes past each
 * match, comments/strings naturally "swallow" any keywords or numbers inside them.
 *
 * Colors are resolved from the Material color scheme at draw time (via [SyntaxColors]) so
 * the same tokenization looks correct in both light and dark themes.
 */
enum class TokenKind { COMMENT, STRING, NUMBER, ANNOTATION, KEYWORD }

/** Precompiled tokenizer for one language: the alternation regex + per-group kinds. */
class LanguageSpec(private val parts: List<Pair<TokenKind, String>>) {
    private val kinds: List<TokenKind> = parts.map { it.first }
    val regex: Regex = Regex(
        parts.joinToString("|") { "(${it.second})" },
        setOf(RegexOption.MULTILINE),
    )

    /** Which token a match belongs to, found by the first non-null capturing group. */
    fun kindFor(match: MatchResult): TokenKind? {
        for (i in kinds.indices) {
            if (match.groups[i + 1] != null) return kinds[i]
        }
        return null
    }
}

enum class Language(val label: String) {
    KOTLIN("Kotlin"),
    JAVA("Java"),
    JAVASCRIPT("JavaScript"),
    TYPESCRIPT("TypeScript"),
    PYTHON("Python"),
    C("C"),
    CPP("C++"),
    RUST("Rust"),
    JSON("JSON"),
    XML("XML"),
    MARKDOWN("Markdown"),
    PLAINTEXT("Plain Text");

    /** The tokenizer for this language, or null for languages we render without colors. */
    val spec: LanguageSpec? by lazy { specFor(this) }

    companion object {
        /** Picks a language from a file name's extension, defaulting to plain text. */
        fun fromFileName(name: String): Language {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "kt", "kts" -> KOTLIN
                "java" -> JAVA
                "js", "jsx", "mjs", "cjs" -> JAVASCRIPT
                "ts", "tsx" -> TYPESCRIPT
                "py", "pyw" -> PYTHON
                "c", "h" -> C
                "cpp", "cc", "cxx", "hpp", "hh", "hxx" -> CPP
                "rs" -> RUST
                "json" -> JSON
                "xml", "html", "htm", "svg", "xhtml" -> XML
                "md", "markdown" -> MARKDOWN
                else -> PLAINTEXT
            }
        }
    }
}

// --- Reusable regex fragments (only non-capturing groups inside each) ---
private const val DOUBLE_STRING = "\"(?:\\\\.|[^\"\\\\\\n])*\""
private const val SINGLE_STRING = "'(?:\\\\.|[^'\\\\\\n])*'"
private const val BACKTICK_STRING = "`(?:\\\\.|[^`\\\\])*`"
private const val TRIPLE_DOUBLE = "\"\"\"[\\s\\S]*?\"\"\""
private const val TRIPLE_SINGLE = "'''[\\s\\S]*?'''"
private const val LINE_COMMENT = "//[^\\n]*"
private const val BLOCK_COMMENT = "/\\*[\\s\\S]*?\\*/"
private const val HASH_COMMENT = "#[^\\n]*"
private const val NUMBER = "\\b(?:0[xX][0-9a-fA-F_]+|\\d[\\d_]*(?:\\.\\d[\\d_]*)?(?:[eE][+-]?\\d+)?[fFlLuU]*)\\b"
private const val ANNOTATION = "@\\w+"

private fun keywords(vararg words: String) = "\\b(?:" + words.joinToString("|") + ")\\b"

private fun specFor(language: Language): LanguageSpec? = when (language) {
    Language.KOTLIN, Language.JAVA -> LanguageSpec(
        listOf(
            TokenKind.COMMENT to BLOCK_COMMENT,
            TokenKind.COMMENT to LINE_COMMENT,
            TokenKind.STRING to TRIPLE_DOUBLE,
            TokenKind.STRING to DOUBLE_STRING,
            TokenKind.STRING to SINGLE_STRING,
            TokenKind.ANNOTATION to ANNOTATION,
            TokenKind.KEYWORD to keywords(
                "abstract", "as", "assert", "boolean", "break", "byte", "case", "catch", "char",
                "class", "companion", "const", "constructor", "continue", "crossinline", "data",
                "default", "do", "double", "dynamic", "else", "enum", "extends", "external",
                "false", "final", "finally", "float", "for", "fun", "get", "goto", "if",
                "implements", "import", "in", "infix", "init", "inline", "inner", "instanceof",
                "int", "interface", "internal", "is", "lateinit", "lazy", "long", "native", "new",
                "null", "object", "open", "operator", "out", "override", "package", "private",
                "protected", "public", "reified", "return", "sealed", "set", "short", "static",
                "strictfp", "super", "suspend", "switch", "synchronized", "tailrec", "this",
                "throw", "throws", "transient", "true", "try", "typealias", "val", "var", "vararg",
                "void", "volatile", "when", "where", "while",
            ),
            TokenKind.NUMBER to NUMBER,
        )
    )

    Language.JAVASCRIPT, Language.TYPESCRIPT -> LanguageSpec(
        listOf(
            TokenKind.COMMENT to BLOCK_COMMENT,
            TokenKind.COMMENT to LINE_COMMENT,
            TokenKind.STRING to DOUBLE_STRING,
            TokenKind.STRING to SINGLE_STRING,
            TokenKind.STRING to BACKTICK_STRING,
            TokenKind.KEYWORD to keywords(
                "abstract", "any", "as", "async", "await", "boolean", "break", "case", "catch",
                "class", "const", "continue", "debugger", "declare", "default", "delete", "do",
                "else", "enum", "export", "extends", "false", "finally", "for", "from", "function",
                "get", "if", "implements", "import", "in", "instanceof", "interface", "let",
                "namespace", "never", "new", "null", "number", "object", "of", "package", "private",
                "protected", "public", "readonly", "return", "set", "static", "string", "super",
                "switch", "this", "throw", "true", "try", "type", "typeof", "undefined", "var",
                "void", "while", "with", "yield",
            ),
            TokenKind.NUMBER to NUMBER,
        )
    )

    Language.PYTHON -> LanguageSpec(
        listOf(
            TokenKind.STRING to TRIPLE_DOUBLE,
            TokenKind.STRING to TRIPLE_SINGLE,
            TokenKind.COMMENT to HASH_COMMENT,
            TokenKind.STRING to DOUBLE_STRING,
            TokenKind.STRING to SINGLE_STRING,
            TokenKind.ANNOTATION to ANNOTATION,
            TokenKind.KEYWORD to keywords(
                "False", "None", "True", "and", "as", "assert", "async", "await", "break", "case",
                "class", "continue", "def", "del", "elif", "else", "except", "finally", "for",
                "from", "global", "if", "import", "in", "is", "lambda", "match", "nonlocal", "not",
                "or", "pass", "raise", "return", "self", "try", "while", "with", "yield",
            ),
            TokenKind.NUMBER to NUMBER,
        )
    )

    Language.C, Language.CPP -> LanguageSpec(
        listOf(
            TokenKind.COMMENT to BLOCK_COMMENT,
            TokenKind.COMMENT to LINE_COMMENT,
            TokenKind.STRING to DOUBLE_STRING,
            TokenKind.STRING to SINGLE_STRING,
            TokenKind.ANNOTATION to "^\\s*#\\s*\\w+", // preprocessor directives
            TokenKind.KEYWORD to keywords(
                "alignas", "alignof", "and", "asm", "auto", "bool", "break", "case", "catch",
                "char", "class", "const", "constexpr", "const_cast", "continue", "decltype",
                "default", "delete", "do", "double", "dynamic_cast", "else", "enum", "explicit",
                "export", "extern", "false", "float", "for", "friend", "goto", "if", "inline",
                "int", "long", "mutable", "namespace", "new", "noexcept", "nullptr", "operator",
                "private", "protected", "public", "register", "reinterpret_cast", "return", "short",
                "signed", "sizeof", "static", "static_assert", "static_cast", "struct", "switch",
                "template", "this", "throw", "true", "try", "typedef", "typeid", "typename",
                "union", "unsigned", "using", "virtual", "void", "volatile", "wchar_t", "while",
            ),
            TokenKind.NUMBER to NUMBER,
        )
    )

    Language.RUST -> LanguageSpec(
        listOf(
            TokenKind.COMMENT to BLOCK_COMMENT,
            TokenKind.COMMENT to LINE_COMMENT,
            TokenKind.STRING to DOUBLE_STRING,
            TokenKind.ANNOTATION to "#!?\\[[^\\]]*\\]", // attributes like #[derive(...)]
            TokenKind.KEYWORD to keywords(
                "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else",
                "enum", "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match",
                "mod", "move", "mut", "pub", "ref", "return", "self", "Self", "static", "struct",
                "super", "trait", "true", "type", "unsafe", "use", "where", "while",
            ),
            TokenKind.NUMBER to NUMBER,
        )
    )

    Language.JSON -> LanguageSpec(
        listOf(
            TokenKind.STRING to DOUBLE_STRING,
            TokenKind.KEYWORD to keywords("true", "false", "null"),
            TokenKind.NUMBER to NUMBER,
        )
    )

    Language.XML -> LanguageSpec(
        listOf(
            TokenKind.COMMENT to "<!--[\\s\\S]*?-->",
            TokenKind.STRING to DOUBLE_STRING,
            TokenKind.STRING to SINGLE_STRING,
            TokenKind.KEYWORD to "</?[?!]?[A-Za-z][\\w:.-]*|/?>",
            TokenKind.NUMBER to "&#?\\w+;", // entities
        )
    )

    Language.MARKDOWN -> LanguageSpec(
        listOf(
            TokenKind.STRING to "```[\\s\\S]*?```",
            TokenKind.STRING to "`[^`\\n]+`",
            TokenKind.KEYWORD to "^#{1,6} .*$",
            TokenKind.NUMBER to "\\[[^\\]\\n]*\\]\\([^)\\n]*\\)", // links
            TokenKind.ANNOTATION to "\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__", // bold
            TokenKind.ANNOTATION to "\\*[^*\\n]+\\*|_[^_\\n]+_",       // italic
            TokenKind.COMMENT to "^>.*$", // blockquote
        )
    )

    Language.PLAINTEXT -> null
}

/** Theme-derived colors for each token kind plus find-match highlights. */
data class SyntaxColors(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val annotation: Color,
    val match: Color,
    val activeMatch: Color,
) {
    fun colorFor(kind: TokenKind): Color = when (kind) {
        TokenKind.COMMENT -> comment
        TokenKind.STRING -> string
        TokenKind.NUMBER -> number
        TokenKind.ANNOTATION -> annotation
        TokenKind.KEYWORD -> keyword
    }
}

@Composable
fun rememberSyntaxColors(): SyntaxColors {
    val scheme = MaterialTheme.colorScheme
    return SyntaxColors(
        keyword = scheme.primary,
        string = scheme.tertiary,
        number = scheme.secondary,
        comment = scheme.onSurfaceVariant,
        annotation = scheme.error,
        match = scheme.primary.copy(alpha = 0.30f),
        activeMatch = scheme.tertiary.copy(alpha = 0.55f),
    )
}

/**
 * A [VisualTransformation] that leaves the text unchanged (identity offset mapping) but
 * layers syntax spans and find-match backgrounds onto it. Highlighting is skipped for
 * very large documents to keep typing responsive.
 */
class SyntaxTransformation(
    private val spec: LanguageSpec?,
    private val colors: SyntaxColors,
    private val matches: List<IntRange> = emptyList(),
    private val activeMatch: Int = -1,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val builder = AnnotatedString.Builder(text)

        if (spec != null && raw.length <= MAX_HIGHLIGHT_LENGTH) {
            for (match in spec.regex.findAll(raw)) {
                val kind = spec.kindFor(match) ?: continue
                builder.addStyle(
                    SpanStyle(color = colors.colorFor(kind)),
                    match.range.first,
                    match.range.last + 1,
                )
            }
        }

        matches.forEachIndexed { index, range ->
            if (range.isEmpty()) return@forEachIndexed
            val background = if (index == activeMatch) colors.activeMatch else colors.match
            builder.addStyle(
                SpanStyle(background = background),
                range.first.coerceIn(0, raw.length),
                (range.last + 1).coerceIn(0, raw.length),
            )
        }

        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }

    private companion object {
        // Above this size, skip tokenization: a single regex pass over hundreds of KB per
        // keystroke would jank. The file still opens and edits fine, just uncolored.
        const val MAX_HIGHLIGHT_LENGTH = 150_000
    }
}
