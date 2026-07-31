package org.schabi.newpipe.extractor.utils

import org.schabi.newpipe.extractor.exceptions.ParsingException
import java.util.regex.Matcher
import java.util.regex.Pattern
import javax.annotation.Nonnull

/**
 * Avoid using regex !!!
 */
object Parser {

    class RegexException(message: String) : ParsingException(message)

    @Nonnull
    @JvmStatic
    @Throws(RegexException::class)
    fun matchOrThrow(pattern: Pattern, input: String): Matcher {
        val matcher = pattern.matcher(input)
        if (matcher.find()) {
            return matcher
        } else {
            var errorMessage = "Failed to find pattern \"${pattern.pattern()}\""
            if (input.length <= 1024) {
                errorMessage += " inside of \"$input\""
            }
            throw RegexException(errorMessage)
        }
    }

    /**
     * Matches group 1 of the given pattern against the input
     * and returns the matched group
     *
     * @param pattern The regex pattern to match.
     * @param input   The input string to match against.
     * @return The matching group as a string.
     * @throws RegexException If the pattern does not match the input or if the group is not found.
     */
    @Nonnull
    @JvmStatic
    @Throws(RegexException::class)
    fun matchGroup1(pattern: String, input: String): String = matchGroup(pattern, input, 1)

    /**
     * Matches group 1 of the given pattern against the input
     * and returns the matched group
     *
     * @param pattern The regex pattern to match.
     * @param input   The input string to match against.
     * @return The matching group as a string.
     * @throws RegexException If the pattern does not match the input or if the group is not found.
     */
    @Nonnull
    @JvmStatic
    @Throws(RegexException::class)
    fun matchGroup1(pattern: Pattern, input: String): String = matchGroup(pattern, input, 1)

    /**
     * Matches the specified group of the given pattern against the input,
     * and returns the matched group
     *
     * @param pattern The regex pattern to match.
     * @param input   The input string to match against.
     * @param group   The group number to retrieve (1-based index).
     * @return The matching group as a string.
     * @throws RegexException If the pattern does not match the input or if the group is not found.
     */
    @Nonnull
    @JvmStatic
    @Throws(RegexException::class)
    fun matchGroup(pattern: String, input: String, group: Int): String =
        matchGroup(Pattern.compile(pattern), input, group)

    /**
     * Matches the specified group of the given pattern against the input,
     * and returns the matched group
     *
     * @param pattern The regex pattern to match.
     * @param input   The input string to match against.
     * @param group   The group number to retrieve (1-based index).
     * @return The matching group as a string.
     * @throws RegexException If the pattern does not match the input or if the group is not found.
     */
    @Nonnull
    @JvmStatic
    @Throws(RegexException::class)
    fun matchGroup(pattern: Pattern, input: String, group: Int): String =
        matchOrThrow(pattern, input).group(group)

    /**
     * Matches multiple patterns against the input string and
     * returns the first successful matcher
     *
     * @param patterns The array of regex patterns to match.
     * @param input    The input string to match against.
     * @return A [Matcher] for the first successful match.
     * @throws RegexException If no patterns match the input or if `patterns` is empty.
     */
    @JvmStatic
    @Throws(RegexException::class)
    fun matchGroup1MultiplePatterns(patterns: Array<Pattern>, input: String): String =
        matchMultiplePatterns(patterns, input).group(1)

    /**
     * Matches multiple patterns against the input string and
     * returns the first successful matcher
     *
     * @param patterns The array of regex patterns to match.
     * @param input    The input string to match against.
     * @return A [Matcher] for the first successful match.
     * @throws RegexException If no patterns match the input or if `patterns` is empty.
     */
    @JvmStatic
    @Throws(RegexException::class)
    fun matchMultiplePatterns(patterns: Array<Pattern>, input: String): Matcher {
        var exception: RegexException? = null
        for (pattern in patterns) {
            val matcher = pattern.matcher(input)
            if (matcher.find()) {
                return matcher
            } else if (exception == null) {
                exception = RegexException(
                    "Failed to find pattern \"${pattern.pattern()}\"" +
                        (if (input.length <= 1000) "inside of \"$input\"" else "")
                )
            }
        }
        throw exception
            ?: RegexException("Empty patterns array passed to matchMultiplePatterns")
    }

    @JvmStatic
    fun isMatch(pattern: String, input: String): Boolean =
        isMatch(Pattern.compile(pattern), input)

    @JvmStatic
    fun isMatch(pattern: Pattern, input: String): Boolean = pattern.matcher(input).find()

    @Nonnull
    @JvmStatic
    fun compatParseMap(input: String): Map<String, String> {
        // The stream collector this replaced used (existing, replacement) -> replacement,
        // so the *last* occurrence of a duplicate key wins.
        val parsed = LinkedHashMap<String, String>()
        for (arg in input.split("&")) {
            // Java's String.split drops trailing empty strings, so "key=" was skipped.
            val splitArg = arg.split("=").dropLastWhile { it.isEmpty() }
            if (splitArg.size > 1) {
                parsed[splitArg[0]] = Utils.decodeUrlUtf8(splitArg[1])
            }
        }
        return parsed
    }
}
