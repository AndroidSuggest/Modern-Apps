package org.schabi.newpipe.extractor.localization

import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.timeago.PatternsHolder
import org.schabi.newpipe.extractor.utils.Parser
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.regex.Pattern

class TimeAgoParser(
    private val patternsHolder: PatternsHolder,
    private val now: LocalDateTime
) {
    @Throws(ParsingException::class)
    fun parse(textualDate: String): DateWrapper {
        for ((chronoUnit, caseMap) in patternsHolder.specialCases().entries) {
            for ((caseText, caseAmount) in caseMap.entries) {
                if (textualDateMatches(textualDate, caseText)) {
                    return getResultFor(caseAmount, chronoUnit)
                }
            }
        }
        return getResultFor(parseTimeAgoAmount(textualDate), parseChronoUnit(textualDate))
    }

    private fun parseTimeAgoAmount(textualDate: String): Int {
        return try {
            textualDate.replace("\\D+".toRegex(), "").toInt()
        } catch (ignored: NumberFormatException) {
            1
        }
    }

    @Throws(ParsingException::class)
    private fun parseChronoUnit(textualDate: String): ChronoUnit {
        return patternsHolder.asMap().entries.firstOrNull { entry ->
            entry.value.any { agoPhrase -> textualDateMatches(textualDate, agoPhrase) }
        }?.key ?: throw ParsingException("Unable to parse the date: $textualDate")
    }

    private fun textualDateMatches(textualDate: String, agoPhrase: String): Boolean {
        if (textualDate == agoPhrase) return true

        if (patternsHolder.wordSeparator().isEmpty()) {
            return textualDate.lowercase().contains(agoPhrase.lowercase())
        }

        val escapedPhrase = Pattern.quote(agoPhrase.lowercase())
        val escapedSeparator = if (patternsHolder.wordSeparator() == " ") {
            "[ \\t\\xA0\\u1680\\u180e\\u2000-\\u200a\\u202f\\u205f\\u3000\\d]"
        } else {
            Pattern.quote(patternsHolder.wordSeparator())
        }

        val pattern = "(^|$escapedSeparator)$escapedPhrase($|$escapedSeparator)"
        return Parser.isMatch(pattern, textualDate.lowercase())
    }

    private fun getResultFor(timeAgoAmount: Int, chronoUnit: ChronoUnit): DateWrapper {
        val localDateTime = if (chronoUnit == ChronoUnit.YEARS) {
            now.minusYears(timeAgoAmount.toLong()).minusDays(1)
        } else {
            now.minus(timeAgoAmount.toLong(), chronoUnit)
        }
        val isApproximate = chronoUnit.isDateBased
        val resolvedDateTime = if (isApproximate) localDateTime.truncatedTo(ChronoUnit.DAYS) else localDateTime
        return DateWrapper(resolvedDateTime, isApproximate)
    }
}
