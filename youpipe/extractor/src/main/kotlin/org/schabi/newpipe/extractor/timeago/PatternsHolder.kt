package org.schabi.newpipe.extractor.timeago

import java.time.temporal.ChronoUnit
import java.util.EnumMap

open class PatternsHolder internal constructor(
    private val wordSeparator: String,
    private val seconds: Collection<String>,
    private val minutes: Collection<String>,
    private val hours: Collection<String>,
    private val days: Collection<String>,
    private val weeks: Collection<String>,
    private val months: Collection<String>,
    private val years: Collection<String>
) {
    private val specialCases: MutableMap<ChronoUnit, MutableMap<String, Int>> =
        EnumMap(ChronoUnit::class.java)

    internal constructor(
        wordSeparator: String,
        seconds: Array<String>,
        minutes: Array<String>,
        hours: Array<String>,
        days: Array<String>,
        weeks: Array<String>,
        months: Array<String>,
        years: Array<String>
    ) : this(
        wordSeparator,
        seconds.asList(),
        minutes.asList(),
        hours.asList(),
        days.asList(),
        weeks.asList(),
        months.asList(),
        years.asList()
    )

    fun wordSeparator(): String = wordSeparator

    fun seconds(): Collection<String> = seconds

    fun minutes(): Collection<String> = minutes

    fun hours(): Collection<String> = hours

    fun days(): Collection<String> = days

    fun weeks(): Collection<String> = weeks

    fun months(): Collection<String> = months

    fun years(): Collection<String> = years

    fun specialCases(): Map<ChronoUnit, Map<String, Int>> = specialCases

    /**
     * Records a phrase that names its own amount, e.g. Hebrew "שעתיים" ("two hours"), which has
     * no digits for [TimeAgoParser] to read.
     *
     * `internal` rather than `protected` because patterns are loaded from
     * `unique_patterns.json` by [PatternsManager]; upstream instead generates a subclass per
     * locale that calls this from its constructor.
     */
    internal fun putSpecialCase(unit: ChronoUnit, caseText: String, caseAmount: Int) {
        val item = specialCases.computeIfAbsent(unit) { LinkedHashMap() }
        item[caseText] = caseAmount
    }

    fun asMap(): Map<ChronoUnit, Collection<String>> {
        val returnMap: MutableMap<ChronoUnit, Collection<String>> = EnumMap(ChronoUnit::class.java)
        returnMap[ChronoUnit.SECONDS] = seconds()
        returnMap[ChronoUnit.MINUTES] = minutes()
        returnMap[ChronoUnit.HOURS] = hours()
        returnMap[ChronoUnit.DAYS] = days()
        returnMap[ChronoUnit.WEEKS] = weeks()
        returnMap[ChronoUnit.MONTHS] = months()
        returnMap[ChronoUnit.YEARS] = years()
        return returnMap
    }
}
