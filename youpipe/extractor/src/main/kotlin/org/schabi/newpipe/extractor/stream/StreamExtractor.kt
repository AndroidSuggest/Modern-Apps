package org.schabi.newpipe.extractor.stream

import org.schabi.newpipe.extractor.Extractor
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.InfoItemExtractor
import org.schabi.newpipe.extractor.InfoItemsCollector
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.MetaInfo
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.LinkHandler
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.utils.Parser
import java.io.IOException
import java.util.Collections
import java.util.Locale

abstract class StreamExtractor(service: StreamingService, linkHandler: LinkHandler) : Extractor(service, linkHandler) {

    companion object {
        const val NO_AGE_LIMIT = 0
        const val UNKNOWN_SUBSCRIBER_COUNT: Long = -1
    }

    @Throws(ParsingException::class)
    open fun getTextualUploadDate(): String? = null

    @Throws(ParsingException::class)
    open fun getUploadDate(): DateWrapper? = null

    @Throws(ParsingException::class)
    abstract fun getThumbnails(): List<Image>

    @Throws(ParsingException::class)
    open fun getDescription(): Description = Description.EMPTY_DESCRIPTION

    @Throws(ParsingException::class)
    open fun getAgeLimit(): Int = NO_AGE_LIMIT

    @Throws(ParsingException::class)
    open fun getLength(): Long = 0

    @Throws(ParsingException::class)
    open fun getTimeStamp(): Long = 0

    @Throws(ParsingException::class)
    open fun getViewCount(): Long = -1

    @Throws(ParsingException::class)
    open fun getLikeCount(): Long = -1

    @Throws(ParsingException::class)
    open fun getDislikeCount(): Long = -1

    @Throws(ParsingException::class)
    abstract fun getUploaderUrl(): String

    @Throws(ParsingException::class)
    abstract fun getUploaderName(): String

    @Throws(ParsingException::class)
    open fun isUploaderVerified(): Boolean = false

    @Throws(ParsingException::class)
    open fun getUploaderSubscriberCount(): Long = UNKNOWN_SUBSCRIBER_COUNT

    @Throws(ParsingException::class)
    open fun getUploaderAvatars(): List<Image> = emptyList()

    @Throws(ParsingException::class)
    open fun getSubChannelUrl(): String = ""

    @Throws(ParsingException::class)
    open fun getSubChannelName(): String = ""

    @Throws(ParsingException::class)
    open fun getSubChannelAvatars(): List<Image> = emptyList()

    @Throws(ParsingException::class)
    open fun getDashMpdUrl(): String = ""

    @Throws(ParsingException::class)
    open fun getHlsUrl(): String = ""

    @Throws(IOException::class, ExtractionException::class)
    abstract fun getAudioStreams(): List<AudioStream>

    @Throws(IOException::class, ExtractionException::class)
    abstract fun getVideoStreams(): List<VideoStream>

    @Throws(IOException::class, ExtractionException::class)
    abstract fun getVideoOnlyStreams(): List<VideoStream>

    @Throws(IOException::class, ExtractionException::class)
    open fun getSubtitlesDefault(): List<SubtitlesStream> = Collections.emptyList()

    @Throws(IOException::class, ExtractionException::class)
    open fun getSubtitles(format: MediaFormat): List<SubtitlesStream> = Collections.emptyList()

    @Throws(ParsingException::class)
    abstract fun getStreamType(): StreamType

    @Throws(IOException::class, ExtractionException::class)
    open fun getRelatedItems(): InfoItemsCollector<out InfoItem, out InfoItemExtractor>? = null

    @Deprecated("Use getRelatedItems()")
    @Throws(IOException::class, ExtractionException::class)
    open fun getRelatedStreams(): StreamInfoItemsCollector? {
        val collector = getRelatedItems()
        return if (collector is StreamInfoItemsCollector) collector else null
    }

    @Throws(ExtractionException::class)
    open fun getFrames(): List<Frameset> = Collections.emptyList()

    open fun getErrorMessage(): String? = null

    @Throws(ParsingException::class)
    protected open fun getTimestampSeconds(regexPattern: String): Long {
        val timestamp: String = try {
            Parser.matchGroup1(regexPattern, getOriginalUrl())
        } catch (e: Parser.RegexException) {
            return -2
        }
        if (timestamp.isNotEmpty()) {
            try {
                var secondsString = ""
                var minutesString = ""
                var hoursString = ""
                try {
                    secondsString = Parser.matchGroup1("(\\d+)s", timestamp)
                    minutesString = Parser.matchGroup1("(\\d+)m", timestamp)
                    hoursString = Parser.matchGroup1("(\\d+)h", timestamp)
                } catch (e: Exception) {
                    if (secondsString.isEmpty() && minutesString.isEmpty()) {
                        secondsString = Parser.matchGroup1("t=(\\d+)", timestamp)
                    }
                }
                val seconds = if (secondsString.isEmpty()) 0 else secondsString.toInt()
                val minutes = if (minutesString.isEmpty()) 0 else minutesString.toInt()
                val hours = if (hoursString.isEmpty()) 0 else hoursString.toInt()
                return (seconds + 60L * minutes + 3600L * hours)
            } catch (e: ParsingException) {
                throw ParsingException("Could not get timestamp.", e)
            }
        }
        return 0
    }

    @Throws(ParsingException::class)
    open fun getHost(): String = ""

    @Throws(ParsingException::class)
    open fun getPrivacy(): Privacy = Privacy.PUBLIC

    @Throws(ParsingException::class)
    open fun getCategory(): String = ""

    @Throws(ParsingException::class)
    open fun getLicence(): String = ""

    @Throws(ParsingException::class)
    open fun getLanguageInfo(): Locale? = null

    @Throws(ParsingException::class)
    open fun getTags(): List<String> = Collections.emptyList()

    @Throws(ParsingException::class)
    open fun getSupportInfo(): String = ""

    @Throws(ParsingException::class)
    open fun getStreamSegments(): List<StreamSegment> = Collections.emptyList()

    @Throws(ParsingException::class)
    open fun getMetaInfo(): List<MetaInfo> = Collections.emptyList()

    @Throws(ParsingException::class)
    open fun isShortFormContent(): Boolean = false

    @Throws(ParsingException::class)
    open fun getContentAvailability(): ContentAvailability = ContentAvailability.UNKNOWN

    enum class Privacy {
        PUBLIC,
        UNLISTED,
        PRIVATE,
        INTERNAL,
        OTHER
    }
}
