package org.schabi.newpipe.extractor

import org.schabi.newpipe.extractor.channel.ChannelInfoItemExtractor
import org.schabi.newpipe.extractor.channel.ChannelInfoItemsCollector
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItemExtractor
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItemsCollector
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector
import java.util.ArrayList
import java.util.Collections

class MultiInfoItemsCollector(serviceId: Int) :
    InfoItemsCollector<InfoItem, InfoItemExtractor>(serviceId) {

    private val streamCollector = StreamInfoItemsCollector(serviceId)
    private val userCollector = ChannelInfoItemsCollector(serviceId)
    private val playlistCollector = PlaylistInfoItemsCollector(serviceId)

    override fun getErrors(): List<Throwable> {
        val allErrors = ArrayList<Throwable>(super.getErrors())
        allErrors.addAll(streamCollector.errors)
        allErrors.addAll(userCollector.errors)
        allErrors.addAll(playlistCollector.errors)
        return Collections.unmodifiableList(allErrors)
    }

    override fun reset() {
        super.reset()
        streamCollector.reset()
        userCollector.reset()
        playlistCollector.reset()
    }

    @Throws(ParsingException::class)
    override fun extract(extractor: InfoItemExtractor): InfoItem {
        return when (extractor) {
            is StreamInfoItemExtractor -> streamCollector.extract(extractor)
            is ChannelInfoItemExtractor -> userCollector.extract(extractor)
            is PlaylistInfoItemExtractor -> playlistCollector.extract(extractor)
            else -> throw IllegalArgumentException("Invalid extractor type: $extractor")
        }
    }
}
