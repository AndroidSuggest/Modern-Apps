package org.schabi.newpipe.extractor.services.youtube.extractors

import org.jsoup.nodes.Element
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor
import org.schabi.newpipe.extractor.stream.StreamType

class YoutubeFeedInfoItemExtractor(
    private val entryElement: Element
) : StreamInfoItemExtractor {

    override fun getStreamType(): StreamType = StreamType.VIDEO_STREAM

    override fun isAd(): Boolean = false

    override fun getDuration(): Long = -1

    override fun getViewCount(): Long {
        return entryElement.getElementsByTag("media:statistics").first()!!
            .attr("views").toLong()
    }

    override fun getUploaderName(): String? {
        return entryElement.select("author > name").first()!!.text()
    }

    override fun getUploaderUrl(): String? {
        return entryElement.select("author > uri").first()!!.text()
    }

    override fun isUploaderVerified(): Boolean = false

    override fun getTextualUploadDate(): String? {
        return entryElement.getElementsByTag("published").first()!!.text()
    }

    @Throws(ParsingException::class)
    override fun getUploadDate(): DateWrapper? {
        return DateWrapper.fromOffsetDateTime(getTextualUploadDate())
    }

    override fun getName(): String {
        return entryElement.getElementsByTag("title").first()!!.text()
    }

    override fun getUrl(): String {
        return entryElement.getElementsByTag("link").first()!!.attr("href")
    }

    override fun getThumbnails(): List<Image> {
        val thumbnailElement = entryElement.getElementsByTag("media:thumbnail").first()
            ?: return emptyList()

        val feedThumbnailUrl = thumbnailElement.attr("url")

        if (feedThumbnailUrl.isEmpty()) {
            return emptyList()
        }

        val newFeedThumbnailUrl = feedThumbnailUrl.replace("hqdefault", "mqdefault")

        val height: Int
        val width: Int

        if (newFeedThumbnailUrl == feedThumbnailUrl) {
            height = try {
                thumbnailElement.attr("height").toInt()
            } catch (e: NumberFormatException) {
                Image.HEIGHT_UNKNOWN
            }

            width = try {
                thumbnailElement.attr("width").toInt()
            } catch (e: NumberFormatException) {
                Image.WIDTH_UNKNOWN
            }
        } else {
            height = 320
            width = 180
        }

        return listOf(
            Image(newFeedThumbnailUrl, height, width, Image.ResolutionLevel.fromHeight(height))
        )
    }
}
