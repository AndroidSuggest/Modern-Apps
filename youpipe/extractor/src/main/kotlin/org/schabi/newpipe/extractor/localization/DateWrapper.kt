package org.schabi.newpipe.extractor.localization

import org.schabi.newpipe.extractor.exceptions.ParsingException
import java.io.Serializable
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import javax.annotation.Nonnull
import javax.annotation.Nullable

class DateWrapper : Serializable {

    @Nonnull
    val instant: Instant
    val isApproximation: Boolean

    constructor(offsetDateTime: OffsetDateTime) : this(offsetDateTime, false)

    constructor(offsetDateTime: OffsetDateTime, isApproximation: Boolean) :
        this(offsetDateTime.toInstant(), isApproximation)

    constructor(instant: Instant) : this(instant, false)

    constructor(instant: Instant, isApproximation: Boolean) {
        this.instant = instant
        this.isApproximation = isApproximation
    }

    constructor(dateTime: LocalDateTime, isApproximation: Boolean) :
        this(dateTime.atZone(ZoneId.systemDefault()).toInstant(), isApproximation)


    @Nonnull
    fun offsetDateTime(): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)

    @Nonnull
    fun getLocalDateTime(): LocalDateTime = getLocalDateTime(ZoneId.systemDefault())

    @Nonnull
    fun getLocalDateTime(@Nonnull zoneId: ZoneId): LocalDateTime =
        LocalDateTime.ofInstant(instant, zoneId)


    override fun toString(): String =
        "DateWrapper{instant=$instant, isApproximation=$isApproximation}"

    companion object {
        @JvmStatic
        @Nullable
        @Throws(ParsingException::class)
        fun fromOffsetDateTime(date: String?): DateWrapper? {
            return try {
                if (date != null) DateWrapper(OffsetDateTime.parse(date)) else null
            } catch (e: DateTimeParseException) {
                throw ParsingException("Could not parse date: \"$date\"", e)
            }
        }

        @JvmStatic
        @Nullable
        @Throws(ParsingException::class)
        fun fromInstant(date: String?): DateWrapper? {
            return try {
                if (date != null) DateWrapper(Instant.parse(date)) else null
            } catch (e: DateTimeParseException) {
                throw ParsingException("Could not parse date: \"$date\"", e)
            }
        }
    }
}
