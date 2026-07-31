package org.schabi.newpipe.extractor.channel

import org.schabi.newpipe.extractor.InfoItemExtractor
import org.schabi.newpipe.extractor.exceptions.ParsingException

interface ChannelInfoItemExtractor : InfoItemExtractor {

    @Throws(ParsingException::class)
    fun getDescription(): String?

    @Throws(ParsingException::class)
    fun getSubscriberCount(): Long

    @Throws(ParsingException::class)
    fun getStreamCount(): Long

    @Throws(ParsingException::class)
    fun isVerified(): Boolean
}
