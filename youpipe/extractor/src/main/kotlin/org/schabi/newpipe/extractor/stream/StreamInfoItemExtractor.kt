package org.schabi.newpipe.extractor.stream

import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItemExtractor
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.localization.DateWrapper

interface StreamInfoItemExtractor : InfoItemExtractor {

    @Throws(ParsingException::class)
    fun getStreamType(): StreamType

    @Throws(ParsingException::class)
    fun isAd(): Boolean

    @Throws(ParsingException::class)
    fun getDuration(): Long

    @Throws(ParsingException::class)
    fun getViewCount(): Long

    @Throws(ParsingException::class)
    fun getUploaderName(): String

    @Throws(ParsingException::class)
    fun getUploaderUrl(): String

    @Throws(ParsingException::class)
    fun getUploaderAvatars(): List<Image> = emptyList()

    @Throws(ParsingException::class)
    fun isUploaderVerified(): Boolean

    @Throws(ParsingException::class)
    fun getTextualUploadDate(): String?

    @Throws(ParsingException::class)
    fun getUploadDate(): DateWrapper?

    @Throws(ParsingException::class)
    fun getShortDescription(): String? = null

    @Throws(ParsingException::class)
    fun isShortFormContent(): Boolean = false

    @Throws(ParsingException::class)
    fun getContentAvailability(): ContentAvailability = ContentAvailability.UNKNOWN
}
