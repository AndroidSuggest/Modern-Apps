package org.schabi.newpipe.extractor.utils

import org.schabi.newpipe.extractor.exceptions.ParsingException
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import java.net.MalformedURLException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.regex.Pattern
import javax.annotation.Nonnull
import javax.annotation.Nullable

object Utils {
    const val HTTP = "http://"
    const val HTTPS = "https://"
    private val M_PATTERN: Pattern = Pattern.compile("(https?)?://m\\.")
    private val WWW_PATTERN: Pattern = Pattern.compile("(https?)?://www\\.")

    /**
     * Encodes a string to URL format using the UTF-8 character set.
     *
     * @param string The string to be encoded.
     * @return The encoded URL.
     */
    @JvmStatic
    fun encodeUrlUtf8(string: String): String = URLEncoder.encode(string, Charsets.UTF_8)

    /**
     * Decodes a URL using the UTF-8 character set.
     * @param url The URL to be decoded.
     * @return The decoded URL.
     */
    @JvmStatic
    fun decodeUrlUtf8(url: String): String = URLDecoder.decode(url, Charsets.UTF_8)

    /**
     * Escape text for embedding in HTML, as element content or inside a double-quoted
     * attribute value.
     */
    @JvmStatic
    fun escapeHtml(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&#39;")
                '\u00a0' -> out.append("&nbsp;")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /**
     * Remove all non-digit characters from a string.
     *
     * Examples:
     * - 1 234 567 views -> 1234567
     * - $31,133.124 -> 31133124
     *
     * @param toRemove string to remove non-digit chars
     * @return a string that contains only digits
     */
    @Nonnull
    @JvmStatic
    fun removeNonDigitCharacters(toRemove: String): String = toRemove.replace("\\D+".toRegex(), "")

    /**
     * Convert a mixed number word to a long.
     *
     * Examples:
     * - 123 -> 123
     * - 1.23K -> 1230
     * - 1.23M -> 1230000
     *
     * @param numberWord string to be converted to a long
     * @return a long
     */
    @JvmStatic
    @Throws(NumberFormatException::class, ParsingException::class)
    fun mixedNumberWordToLong(numberWord: String): Long {
        var multiplier = ""
        try {
            multiplier = Parser.matchGroup("[\\d]+([\\.,][\\d]+)?([KMBkmb])+", numberWord, 2)
        } catch (_: ParsingException) {
        }
        val count = Parser.matchGroup1("([\\d]+([\\.,][\\d]+)?)", numberWord)
            .replace(",", ".").toDouble()
        return when (multiplier.uppercase()) {
            "K" -> (count * 1e3).toLong()
            "M" -> (count * 1e6).toLong()
            "B" -> (count * 1e9).toLong()
            else -> count.toLong()
        }
    }

    /**
     * Check if the url matches the pattern.
     *
     * @param pattern the pattern that will be used to check the url
     * @param url     the url to be tested
     */
    @JvmStatic
    @Throws(ParsingException::class)
    fun checkUrl(pattern: String, url: String) {
        checkUrl(Pattern.compile(pattern), url)
    }

    /**
     * Check if the url matches the pattern.
     *
     * @param pattern the pattern that will be used to check the url
     * @param url     the url to be tested
     */
    @JvmStatic
    @Throws(ParsingException::class)
    fun checkUrl(pattern: Pattern, url: String) {
        require(!isNullOrEmpty(url)) { "Url can't be null or empty" }
        if (!Parser.isMatch(pattern, url.lowercase())) {
            throw ParsingException("Url doesn't match the pattern")
        }
    }

    @JvmStatic
    fun replaceHttpWithHttps(url: String?): String? {
        if (url == null) return null
        return if (url.startsWith(HTTP)) {
            HTTPS + url.substring(HTTP.length)
        } else url
    }

    /**
     * Get the value of a URL-query by name.
     *
     * If an url-query is given multiple times, only the value of the first query is returned.
     *
     * @param url           the url to be used
     * @param parameterName the pattern that will be used to check the url
     * @return a string that contains the value of the query parameter or `null` if nothing was found
     */
    @Nullable
    @JvmStatic
    fun getQueryValue(url: URL, parameterName: String): String? {
        val urlQuery = url.query
        if (urlQuery != null) {
            for (param in urlQuery.split("&")) {
                val params = param.split("=", limit = 2)
                val query = decodeUrlUtf8(params[0])
                if (query == parameterName) {
                    return decodeUrlUtf8(params[1])
                }
            }
        }
        return null
    }

    /**
     * Convert a string to a [URL] object.
     *
     * Defaults to HTTP if no protocol is given.
     *
     * @param url the string to be converted to a URL-Object
     * @return a [URL] object containing the url
     */
    @Nonnull
    @JvmStatic
    @Throws(MalformedURLException::class)
    fun stringToURL(url: String): URL {
        try {
            return URL(url)
        } catch (e: MalformedURLException) {
            if (e.message == "no protocol: $url") {
                return URL(HTTPS + url)
            }
            throw e
        }
    }

    @JvmStatic
    fun isHTTP(url: URL): Boolean {
        val protocol = url.protocol
        if (protocol != "http" && protocol != "https") return false
        val usesDefaultPort = url.port == url.defaultPort
        val setsNoPort = url.port == -1
        return setsNoPort || usesDefaultPort
    }

    @JvmStatic
    fun removeMAndWWWFromUrl(url: String): String {
        if (M_PATTERN.matcher(url).find()) {
            return url.replace("m.", "")
        }
        if (WWW_PATTERN.matcher(url).find()) {
            return url.replace("www.", "")
        }
        return url
    }

    @Nonnull
    @JvmStatic
    fun removeUTF8BOM(s: String): String {
        var result = s
        if (result.startsWith("\uFEFF")) result = result.substring(1)
        if (result.endsWith("\uFEFF")) result = result.substring(0, result.length - 1)
        return result
    }

    @Nonnull
    @JvmStatic
    @Throws(ParsingException::class)
    fun getBaseUrl(url: String): String {
        try {
            val uri = stringToURL(url)
            return "${uri.protocol}://${uri.authority}"
        } catch (e: MalformedURLException) {
            val message = e.message ?: ""
            if (message.startsWith("unknown protocol: ")) {
                return message.substring("unknown protocol: ".length)
            }
            throw ParsingException("Malformed url: $url", e)
        }
    }

    /**
     * If the provided url is a Google search redirect, then the actual url is extracted from the
     * `url=` query value and returned, otherwise the original url is returned.
     *
     * @param url the url which can possibly be a Google search redirect
     * @return an url with no Google search redirects
     */
    @JvmStatic
    fun followGoogleRedirectIfNeeded(url: String): String {
        try {
            val decoded = stringToURL(url)
            if (decoded.host.contains("google") && decoded.path == "/url") {
                return decodeUrlUtf8(Parser.matchGroup1("&url=([^&]+)(?:&|$)", url))
            }
        } catch (_: Exception) {
        }
        return url
    }

    // The contracts let callers keep the `if (!isNullOrEmpty(x)) use(x)` shape they had in Java
    // and still get a smart cast to the non-null type.
    @JvmStatic
    @OptIn(ExperimentalContracts::class)
    fun isNullOrEmpty(str: String?): Boolean {
        contract { returns(false) implies (str != null) }
        return str == null || str.isEmpty()
    }

    @JvmStatic
    @OptIn(ExperimentalContracts::class)
    fun isNullOrEmpty(collection: Collection<*>?): Boolean {
        contract { returns(false) implies (collection != null) }
        return collection == null || collection.isEmpty()
    }

    @JvmStatic
    @OptIn(ExperimentalContracts::class)
    fun <K, V> isNullOrEmpty(map: Map<K, V>?): Boolean {
        contract { returns(false) implies (map != null) }
        return map == null || map.isEmpty()
    }

    @JvmStatic
    fun isBlank(string: String?): Boolean = string == null || string.isBlank()

    @Nonnull
    @JvmStatic
    fun join(
        delimiter: String,
        mapJoin: String,
        elements: Map<out CharSequence, CharSequence>
    ): String {
        return elements.entries.joinToString(delimiter) { entry ->
            entry.key.toString() + mapJoin + entry.value
        }
    }

    /**
     * Concatenate all non-null, non-empty and strings which are not equal to `"null"`.
     */
    @Nonnull
    @JvmStatic
    fun nonEmptyAndNullJoin(delimiter: CharSequence, vararg elements: String): String {
        return elements.filter { s -> !isNullOrEmpty(s) && s != "null" }
            .joinToString(delimiter)
    }

    /**
     * Find the result of an array of string regular expressions inside an input on the first group.
     *
     * @param input   the input on which using the regular expressions
     * @param regexes the string array of regular expressions
     * @return the result
     * @throws Parser.RegexException if none of the patterns match the input
     */
    @Nonnull
    @JvmStatic
    @Throws(Parser.RegexException::class)
    fun getStringResultFromRegexArray(input: String, regexes: Array<String>): String =
        getStringResultFromRegexArray(input, regexes, 0)

    /**
     * Find the result of an array of [Pattern]s inside an input on the first group.
     *
     * @param input   the input on which using the regular expressions
     * @param regexes the [Pattern] array
     * @return the result
     * @throws Parser.RegexException if none of the patterns match the input
     */
    @Nonnull
    @JvmStatic
    @Throws(Parser.RegexException::class)
    fun getStringResultFromRegexArray(input: String, regexes: Array<Pattern>): String =
        getStringResultFromRegexArray(input, regexes, 0)

    /**
     * Find the result of an array of string regular expressions inside an input on a specific group.
     *
     * @param input   the input on which using the regular expressions
     * @param regexes the string array of regular expressions
     * @param group   the group to match
     * @return the result
     * @throws Parser.RegexException if none of the patterns match the input
     */
    @Nonnull
    @JvmStatic
    @Throws(Parser.RegexException::class)
    fun getStringResultFromRegexArray(input: String, regexes: Array<String>, group: Int): String {
        return getStringResultFromRegexArray(
            input,
            regexes.filterNotNull().map { Pattern.compile(it) }.toTypedArray(),
            group
        )
    }

    /**
     * Find the result of an array of [Pattern]s inside an input on a specific group.
     *
     * @param input   the input on which using the regular expressions
     * @param regexes the [Pattern] array
     * @param group   the group to match
     * @return the result
     * @throws Parser.RegexException if none of the patterns match the input
     */
    @Nonnull
    @JvmStatic
    @Throws(Parser.RegexException::class)
    fun getStringResultFromRegexArray(input: String, regexes: Array<Pattern>, group: Int): String {
        for (regex in regexes) {
            try {
                return Parser.matchGroup(regex, input, group)
            } catch (_: Parser.RegexException) {
            }
        }
        throw Parser.RegexException("No regex matched the input on group $group")
    }
}
