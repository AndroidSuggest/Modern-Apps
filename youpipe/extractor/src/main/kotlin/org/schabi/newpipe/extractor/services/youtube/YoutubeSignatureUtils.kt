package org.schabi.newpipe.extractor.services.youtube

import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.utils.JavaScript
import org.schabi.newpipe.extractor.utils.Pair
import org.schabi.newpipe.extractor.utils.Parser
import org.schabi.newpipe.extractor.utils.Parser.matchMultiplePatterns
import org.schabi.newpipe.extractor.utils.jsextractor.JavaScriptExtractor
import java.util.regex.Pattern
import javax.annotation.Nonnull

/**
 * Utility class to get the signature timestamp of YouTube's base JavaScript player and deobfuscate
 * signature of streaming URLs from HTML5 clients.
 */
internal object YoutubeSignatureUtils {

    const val DEOBFUSCATION_FUNCTION_NAME = "deobfuscate"

    private val FUNCTION_REGEXES: Array<Pattern> = arrayOf(
        Pattern.compile("\\b(?:[a-zA-Z0-9_\$]+)&&\\((?:[a-zA-Z0-9_\$]+)=([a-zA-Z0-9_\$]{2,})\\((\\d+,)decodeURIComponent\\((?:[a-zA-Z0-9_\$]+)\\)\\)"),
        Pattern.compile("\\b(?:[a-zA-Z0-9_\$]+)&&\\((?:[a-zA-Z0-9_\$]+)=([a-zA-Z0-9_\$]{2,})\\(decodeURIComponent\\((?:[a-zA-Z0-9_\$]+)\\)\\)"),
        Pattern.compile("\\bm=([a-zA-Z0-9\$]{2,})\\(decodeURIComponent\\(h\\.s\\)\\)"),
        Pattern.compile("\\bc&&\\(c=([a-zA-Z0-9\$]{2,})\\(decodeURIComponent\\(c\\)\\)"),
        Pattern.compile("(?:\\b|[^a-zA-Z0-9\$])([a-zA-Z0-9\$]{2,})\\s*=\\s*function\\(\\s*a\\s*\\)\\s*\\{\\s*a\\s*=\\s*a\\.split\\(\\s*\"\"\\s*\\)"),
        Pattern.compile("([\\w\$]+)\\s*=\\s*function\\((\\w+)\\)\\{\\s*\\2=\\s*\\2\\.split\\(\"\"\\)\\s*;")
    )

    private const val STS_REGEX = "signatureTimestamp[=:](\\d+)"

    private const val DEOBF_FUNC_REGEX_START = "("
    private const val DEOBF_FUNC_REGEX_END = "=function\\([a-zA-Z0-9_]+\\)\\{.+?\\})"

    private val SIG_DEOBF_GLOBAL_ARRAY_REGEX =
        Pattern.compile("(var [A-z]=['\"].*['\"].split\\(\"[;{]\"\\))")
    private val SIG_DEOBF_HELPER_OBJ_NAME_REGEX =
        Pattern.compile("[;,]([A-Za-z0-9_\$]{2,})\\[..")
    private const val SIG_DEOBF_HELPER_OBJ_REGEX_START = "(var "
    private const val SIG_DEOBF_HELPER_OBJ_REGEX_END = "=\\{(?>.|\\n)+?\\}\\};)"

    @Nonnull
    @JvmStatic
    @Throws(ParsingException::class)
    fun getSignatureTimestamp(@Nonnull javaScriptPlayerCode: String): String {
        try {
            return Parser.matchGroup1(STS_REGEX, javaScriptPlayerCode)
        } catch (e: ParsingException) {
            throw ParsingException("Could not extract signature timestamp from JavaScript code", e)
        }
    }

    @Nonnull
    @JvmStatic
    @Throws(ParsingException::class)
    fun getDeobfuscationCode(@Nonnull javaScriptPlayerCode: String): String {
        try {
            val deobfuscationFunctionNameAndParams = getDeobfuscationFunctionNameAndParams(javaScriptPlayerCode)
            val deobfuscationFunctionName = deobfuscationFunctionNameAndParams.getFirst()
            val functionAdditionalParams = deobfuscationFunctionNameAndParams.getSecond()

            val deobfuscationFunction: String = try {
                getDeobfuscateFunctionWithLexer(javaScriptPlayerCode, deobfuscationFunctionName)
            } catch (e: Exception) {
                getDeobfuscateFunctionWithRegex(javaScriptPlayerCode, deobfuscationFunctionName)
            }

            JavaScript.compileOrThrow(deobfuscationFunction)

            val globalVar = Parser.matchGroup1(SIG_DEOBF_GLOBAL_ARRAY_REGEX, javaScriptPlayerCode)
            val helperObjectName = Parser.matchGroup1(SIG_DEOBF_HELPER_OBJ_NAME_REGEX, deobfuscationFunction)
            val helperObject = getHelperObject(javaScriptPlayerCode, helperObjectName)

            val callerFunction = "function $DEOBFUSCATION_FUNCTION_NAME(a){return $deobfuscationFunctionName(${functionAdditionalParams}a);}"

            return "$globalVar;$helperObject$deobfuscationFunction;$callerFunction"
        } catch (e: Exception) {
            throw ParsingException("Could not parse deobfuscation function", e)
        }
    }

    @Nonnull
    @Throws(ParsingException::class)
    private fun getDeobfuscationFunctionNameAndParams(@Nonnull javaScriptPlayerCode: String): Pair<String, String> {
        try {
            val m = matchMultiplePatterns(FUNCTION_REGEXES, javaScriptPlayerCode)
            val functionName = m.group(1)
            val functionAdditionalParams = if (m.groupCount() > 1) {
                m.group(2) ?: ""
            } else {
                ""
            }
            return Pair(functionName, functionAdditionalParams)
        } catch (e: Parser.RegexException) {
            throw ParsingException("Could not find deobfuscation function with any of the known patterns", e)
        }
    }

    @Nonnull
    @Throws(ParsingException::class)
    private fun getDeobfuscateFunctionWithLexer(
        @Nonnull javaScriptPlayerCode: String,
        @Nonnull deobfuscationFunctionName: String
    ): String {
        val functionBase = "$deobfuscationFunctionName=function"
        return functionBase + JavaScriptExtractor.matchToClosingBrace(javaScriptPlayerCode, functionBase)
    }

    @Nonnull
    @Throws(ParsingException::class)
    private fun getDeobfuscateFunctionWithRegex(
        @Nonnull javaScriptPlayerCode: String,
        @Nonnull deobfuscationFunctionName: String
    ): String {
        val functionPattern = DEOBF_FUNC_REGEX_START +
            Pattern.quote(deobfuscationFunctionName) + DEOBF_FUNC_REGEX_END
        return "var " + Parser.matchGroup1(functionPattern, javaScriptPlayerCode)
    }

    @Nonnull
    @Throws(ParsingException::class)
    private fun getHelperObject(
        @Nonnull javaScriptPlayerCode: String,
        @Nonnull helperObjectName: String
    ): String {
        val helperPattern = SIG_DEOBF_HELPER_OBJ_REGEX_START +
            Pattern.quote(helperObjectName) + SIG_DEOBF_HELPER_OBJ_REGEX_END
        return Parser.matchGroup1(helperPattern, javaScriptPlayerCode).replace("\n", "")
    }
}
