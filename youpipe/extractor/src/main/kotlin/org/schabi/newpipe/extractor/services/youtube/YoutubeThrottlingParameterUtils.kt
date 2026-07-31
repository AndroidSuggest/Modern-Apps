package org.schabi.newpipe.extractor.services.youtube

import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.utils.JavaScript
import org.schabi.newpipe.extractor.utils.Parser
import org.schabi.newpipe.extractor.utils.Parser.matchMultiplePatterns
import org.schabi.newpipe.extractor.utils.jsextractor.JavaScriptExtractor
import java.util.regex.Matcher
import java.util.regex.Pattern
import javax.annotation.Nonnull
import javax.annotation.Nullable

/**
 * Utility class to get the throttling parameter decryption code and check if a streaming has the
 * throttling parameter.
 */
internal object YoutubeThrottlingParameterUtils {

    // NOTE: When changing this you should also change the quick exit/shortcut
    // in getThrottlingParameterFromStreamingUrl
    private val THROTTLING_PARAM_PATTERN: Pattern = Pattern.compile("[&?]n=([^&]+)")

    private const val SINGLE_CHAR_VARIABLE_REGEX = "[a-zA-Z0-9\$_]"
    private const val MULTIPLE_CHARS_REGEX = SINGLE_CHAR_VARIABLE_REGEX + "+"
    private const val ARRAY_ACCESS_REGEX = "\\[(\\d+)]"

    private val DEOBFUSCATION_FUNCTION_NAME_REGEXES: Array<Pattern> = arrayOf(
        Pattern.compile("([A-Za-z0-9_\\\$]{2,})=function.*return [A-Z]\\[\\d+\\]"),
        Pattern.compile(
            SINGLE_CHAR_VARIABLE_REGEX + "=\"nn\"\\[\\+" + MULTIPLE_CHARS_REGEX +
                "\\." + MULTIPLE_CHARS_REGEX + "]," + MULTIPLE_CHARS_REGEX + "\\(" +
                MULTIPLE_CHARS_REGEX + "\\)," + MULTIPLE_CHARS_REGEX + "=" +
                MULTIPLE_CHARS_REGEX + "\\." + MULTIPLE_CHARS_REGEX + "\\[" +
                MULTIPLE_CHARS_REGEX + "]\\|\\|null\\)&&\\(" + MULTIPLE_CHARS_REGEX + "=(" +
                MULTIPLE_CHARS_REGEX + ")" + ARRAY_ACCESS_REGEX
        ),
        Pattern.compile(
            SINGLE_CHAR_VARIABLE_REGEX + "=\"nn\"\\[\\+" + MULTIPLE_CHARS_REGEX +
                "\\." + MULTIPLE_CHARS_REGEX + "]," + MULTIPLE_CHARS_REGEX + "\\(" +
                MULTIPLE_CHARS_REGEX + "\\)," + MULTIPLE_CHARS_REGEX + "=" +
                MULTIPLE_CHARS_REGEX + "\\." + MULTIPLE_CHARS_REGEX + "\\[" +
                MULTIPLE_CHARS_REGEX + "]\\|\\|null\\).+\\|\\|(" + MULTIPLE_CHARS_REGEX +
                ")\\(\"\"\\)"
        ),
        Pattern.compile(
            "," + MULTIPLE_CHARS_REGEX + "\\(" + MULTIPLE_CHARS_REGEX + "\\)," +
                MULTIPLE_CHARS_REGEX + "=" + MULTIPLE_CHARS_REGEX + "\\." +
                MULTIPLE_CHARS_REGEX + "\\[" + MULTIPLE_CHARS_REGEX + "]\\|\\|null\\)&&\\(\\b" +
                MULTIPLE_CHARS_REGEX + "=(" + MULTIPLE_CHARS_REGEX + ")" +
                ARRAY_ACCESS_REGEX + "\\(" + SINGLE_CHAR_VARIABLE_REGEX + "\\)," +
                MULTIPLE_CHARS_REGEX + "\\.set\\((?:\"n+\"|" + MULTIPLE_CHARS_REGEX + ")," +
                MULTIPLE_CHARS_REGEX + "\\)"
        ),
        Pattern.compile(
            SINGLE_CHAR_VARIABLE_REGEX + "=\"nn\"\\[\\+" + MULTIPLE_CHARS_REGEX +
                "\\." + MULTIPLE_CHARS_REGEX + "]," + MULTIPLE_CHARS_REGEX + "=" +
                MULTIPLE_CHARS_REGEX + "\\.get\\(" + MULTIPLE_CHARS_REGEX + "\\)\\).+\\|\\|(" +
                MULTIPLE_CHARS_REGEX + ")\\(\"\"\\)"
        ),
        Pattern.compile(
            SINGLE_CHAR_VARIABLE_REGEX + "=\"nn\"\\[\\+" + MULTIPLE_CHARS_REGEX +
                "\\." + MULTIPLE_CHARS_REGEX + "]," + MULTIPLE_CHARS_REGEX + "=" +
                MULTIPLE_CHARS_REGEX + "\\.get\\(" + MULTIPLE_CHARS_REGEX + "\\)\\)&&\\(" +
                MULTIPLE_CHARS_REGEX + "=(" + MULTIPLE_CHARS_REGEX + ")\\[(\\d+)]"
        ),
        Pattern.compile(
            "\\(" + SINGLE_CHAR_VARIABLE_REGEX + "=String\\.fromCharCode\\(110\\)," +
                SINGLE_CHAR_VARIABLE_REGEX + "=" + SINGLE_CHAR_VARIABLE_REGEX + "\\.get\\(" +
                SINGLE_CHAR_VARIABLE_REGEX + "\\)\\)&&\\(" + SINGLE_CHAR_VARIABLE_REGEX +
                "=(" + MULTIPLE_CHARS_REGEX + ")" + "(?:" + ARRAY_ACCESS_REGEX + ")?\\(" +
                SINGLE_CHAR_VARIABLE_REGEX + "\\)"
        ),
        Pattern.compile(
            "\\.get\\(\"n\"\\)\\)&&\\(" + SINGLE_CHAR_VARIABLE_REGEX + "=(" +
                MULTIPLE_CHARS_REGEX + ")(?:" + ARRAY_ACCESS_REGEX + ")?\\(" +
                SINGLE_CHAR_VARIABLE_REGEX + "\\)"
        )
    )

    @Suppress("RegExpRedundantEscape")
    private const val DEOBFUSCATION_FUNCTION_BODY_REGEX =
        "=\\s*function([\\S\\s]*?\\}\\s*return [\\w\$]+?\\.join\\(\"\"\\)\\s*\\};)"

    private const val DEOBFUSCATION_FUNCTION_ARRAY_OBJECT_TYPE_DECLARATION_REGEX = "var "
    private const val FUNCTION_NAMES_IN_DEOBFUSCATION_ARRAY_REGEX = "\\s*=\\s*\\[(.+?)][;,]"
    private const val FUNCTION_ARGUMENTS_REGEX = "=\\s*function\\s*\\(\\s*([^)]*)\\s*\\)"
    private const val EARLY_RETURN_REGEX =
        ";\\s*if\\s*\\(\\s*typeof\\s+" + MULTIPLE_CHARS_REGEX +
            "+\\s*===?\\s*([\"'])undefined\\1\\s*\\)\\s*return\\s+"

    @Nonnull
    @JvmStatic
    @Throws(ParsingException::class)
    fun getDeobfuscationFunctionName(@Nonnull javaScriptPlayerCode: String): String {
        val matcher: Matcher
        try {
            matcher = matchMultiplePatterns(DEOBFUSCATION_FUNCTION_NAME_REGEXES, javaScriptPlayerCode)
        } catch (e: Parser.RegexException) {
            throw ParsingException(
                "Could not find deobfuscation function with any of the known patterns in the base JavaScript player code", e
            )
        }

        val functionName = matcher.group(1)
        if (matcher.groupCount() == 1) {
            return functionName
        }

        val arrayNum = matcher.group(2).toInt()
        val arrayPattern = Pattern.compile(
            DEOBFUSCATION_FUNCTION_ARRAY_OBJECT_TYPE_DECLARATION_REGEX +
                Pattern.quote(functionName) + FUNCTION_NAMES_IN_DEOBFUSCATION_ARRAY_REGEX
        )
        val arrayStr = Parser.matchGroup1(arrayPattern, javaScriptPlayerCode)
        val names = arrayStr.split(",")
        return names[arrayNum]
    }

    @Nonnull
    @JvmStatic
    @Throws(ParsingException::class)
    fun getDeobfuscationFunction(
        @Nonnull javaScriptPlayerCode: String,
        @Nonnull functionName: String
    ): String {
        var function: String
        try {
            function = parseFunctionWithLexer(javaScriptPlayerCode, functionName)
        } catch (e: Exception) {
            function = parseFunctionWithRegex(javaScriptPlayerCode, functionName)
        }
        return fixupFunction(function)
    }

    @Nullable
    @JvmStatic
    fun getThrottlingParameterFromStreamingUrl(@Nonnull streamingUrl: String): String? {
        if (!streamingUrl.contains("&n=") && !streamingUrl.contains("?n=")) {
            return null
        }
        return try {
            Parser.matchGroup1(THROTTLING_PARAM_PATTERN, streamingUrl)
        } catch (e: Parser.RegexException) {
            null
        }
    }

    @Nonnull
    @Throws(ParsingException::class)
    private fun parseFunctionWithLexer(
        @Nonnull javaScriptPlayerCode: String,
        @Nonnull functionName: String
    ): String {
        val functionBase = "$functionName=function"
        return functionBase + JavaScriptExtractor.matchToClosingBrace(javaScriptPlayerCode, functionBase) + ";"
    }

    @Nonnull
    @Throws(Parser.RegexException::class)
    private fun parseFunctionWithRegex(
        @Nonnull javaScriptPlayerCode: String,
        @Nonnull functionName: String
    ): String {
        val functionPattern = Pattern.compile(
            Pattern.quote(functionName) + DEOBFUSCATION_FUNCTION_BODY_REGEX,
            Pattern.DOTALL
        )
        return validateFunction("function $functionName" + Parser.matchGroup1(functionPattern, javaScriptPlayerCode))
    }

    @Nonnull
    private fun validateFunction(@Nonnull function: String): String {
        JavaScript.compileOrThrow(function)
        return function
    }

    @Nonnull
    @Throws(Parser.RegexException::class)
    private fun fixupFunction(@Nonnull function: String): String {
        val firstArgName = Parser.matchGroup1(FUNCTION_ARGUMENTS_REGEX, function)
            .split(",")[0].trim()
        val earlyReturnPattern = Pattern.compile(EARLY_RETURN_REGEX + firstArgName + ";", Pattern.DOTALL)
        val earlyReturnCodeMatcher = earlyReturnPattern.matcher(function)
        return earlyReturnCodeMatcher.replaceFirst(";")
    }
}
