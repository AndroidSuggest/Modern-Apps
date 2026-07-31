package org.schabi.newpipe.extractor.localization

import org.schabi.newpipe.extractor.timeago.PatternsHolder
import org.schabi.newpipe.extractor.timeago.PatternsManager
import java.time.LocalDateTime
import javax.annotation.Nonnull
import javax.annotation.Nullable

object TimeAgoPatternsManager {

    @Nullable
    private fun getPatternsFor(@Nonnull localization: Localization): PatternsHolder? =
        PatternsManager.getPatterns(localization.languageCode, localization.countryCode)

    @JvmStatic
    @Nullable
    fun getTimeAgoParserFor(@Nonnull localization: Localization): TimeAgoParser? =
        getTimeAgoParserFor(localization, LocalDateTime.now())

    @JvmStatic
    @Nullable
    fun getTimeAgoParserFor(
        @Nonnull localization: Localization,
        @Nonnull now: LocalDateTime
    ): TimeAgoParser? {
        val holder = getPatternsFor(localization) ?: return null
        return TimeAgoParser(holder, now)
    }
}
