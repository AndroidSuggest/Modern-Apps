package org.schabi.newpipe.extractor

import org.schabi.newpipe.extractor.exceptions.ParsingException
import javax.annotation.Nonnull

interface InfoItemExtractor {
    @Throws(ParsingException::class)
    fun getName(): String

    @Throws(ParsingException::class)
    fun getUrl(): String

    @Throws(ParsingException::class)
    @Nonnull
    fun getThumbnails(): List<Image>
}
