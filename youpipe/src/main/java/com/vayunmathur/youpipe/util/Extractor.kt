package com.vayunmathur.youpipe.util
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.youpipe.ui.ChannelInfo
import com.vayunmathur.youpipe.ui.VideoInfo
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.nio.ByteBuffer
import kotlin.io.encoding.Base64
import kotlin.time.toKotlinInstant

@Serializable
data class SponsorSegment(
    val category: String,
    val segment: List<Float>,
    val UUID: String
) {
    val start: Long get() = (segment[0] * 1000).toLong()
    val end: Long get() = (segment[1] * 1000).toLong()
}

/** Decodes HTML entities/markup to plain text. */
fun String.decodeHtml(): String =
    HtmlCompat.fromHtml(this, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()

/** Maps a NewPipe [StreamInfoItem] to a [VideoInfo], or null if it has no upload date. */
fun StreamInfoItem.toVideoInfo(): VideoInfo? {
    val date = getUploadDate() ?: return null
    return VideoInfo(
        name.decodeHtml(),
        videoURLtoID(url),
        getDuration(),
        getViewCount(),
        date.instant.toKotlinInstant(),
        thumbnails.firstOrNull()?.url ?: "",
        getUploaderName().orEmpty().decodeHtml()
    )
}

fun videoURLtoID(url: String): Long {
    return ByteBuffer.wrap(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(url.toUri().getQueryParameter("v")!!)).long
}

fun channelURLtoID(url: String): String {
    return url.substringAfterLast("/")
}

fun encodeVideoID(id: String): Long {
    return ByteBuffer.wrap(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(id)).long
}

fun decodeVideoID(id: Long): String {
    val buffer = ByteBuffer.allocate(java.lang.Long.BYTES)
    buffer.putLong(id)
    return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(buffer.array())
}

fun videoIDtoURL(id: Long): String = "https://www.youtube.com/watch?v=${decodeVideoID(id)}"

fun channelIDtoURL(id: String): String {
    return if (id.startsWith("@")) {
        "https://www.youtube.com/$id"
    } else {
        "https://www.youtube.com/channel/$id"
    }
}

suspend fun getVideoInfo(videoId: Long): VideoInfo = coroutineScope {
    val idString = decodeVideoID(videoId)
    val ex = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$idString")
    ex.fetchPage()
    VideoInfo(
        ex.getName().decodeHtml(),
        videoId,
        ex.getLength(),
        ex.getViewCount(),
        ex.getUploadDate()!!.instant.toKotlinInstant(),
        ex.getThumbnails().first().url,
        ex.getUploaderName().decodeHtml()
    )
}

fun getChannelVideos(channelId: String): Sequence<VideoInfo> = sequence {
    val ex = ServiceList.YouTube.getChannelTabExtractorFromId(channelId, "videos")
    ex.fetchPage()
    var page = ex.getInitialPage()
    while(true) {
        page.getItems().filterIsInstance<StreamInfoItem>().forEach { item ->
            item.toVideoInfo()?.let { yield(it) }
        }
        if(page.hasNextPage()) {
            val next = page.nextPage ?: break
            page = ex.getPage(next)
        } else {
            break
        }
    }
}

/** First page of the YouTube Trending kiosk, mapped to [VideoInfo]. */
suspend fun getTrendingVideos(): List<VideoInfo> = coroutineScope {
    val ex = ServiceList.YouTube.getKioskList().getDefaultKioskExtractor()
        ?: return@coroutineScope emptyList()
    ex.fetchPage()
    ex.getInitialPage().getItems().filterIsInstance<StreamInfoItem>().mapNotNull { it.toVideoInfo() }
}

/** First page of video results for [query], mapped to [VideoInfo]. */
suspend fun searchVideos(query: String): List<VideoInfo> = coroutineScope {
    val ex = ServiceList.YouTube.getSearchExtractor(query)
    ex.fetchPage()
    ex.getInitialPage().getItems().filterIsInstance<StreamInfoItem>().mapNotNull { it.toVideoInfo() }
}

suspend fun getChannelInfo(channelId: String): ChannelInfo = getChannelInfoFromURL(channelIDtoURL(channelId))

suspend fun getChannelInfoFromURL(url: String): ChannelInfo = coroutineScope {
    val ex = ServiceList.YouTube.getChannelExtractor(url)
    ex.fetchPage()
    ChannelInfo(
        ex.getName().decodeHtml(),
        ex.getId(),
        ex.getSubscriberCount(),
        0,
        ex.getAvatars().firstOrNull()?.url ?: "",
    )
}

@Serializable
data class DeArrowTitle(
    val title: String,
    val original: Boolean,
    val votes: Int,
    val locked: Boolean,
    val UUID: String,
)

@Serializable
data class DeArrowThumbnail(
    val timestamp: Double? = null,
    val original: Boolean,
    val votes: Int,
    val locked: Boolean,
    val UUID: String,
)

@Serializable
data class DeArrowBranding(
    val titles: List<DeArrowTitle>,
    val thumbnails: List<DeArrowThumbnail>,
    val randomTime: Double,
    val videoDuration: Double? = null,
)

/**
 * SponsorBlock + DeArrow data APIs are mirrored on the self-hosted proxy
 * (see location_share_server `sb_build.sh` + handlers/sponsorblock.rs), served
 * from a local copy of the SponsorBlock database. DeArrow thumbnail *frames*
 * are now self-hosted via the same origin (yt-dlp + ffmpeg renderer implemented
 * in `location_share_server/src/handlers/dearrow.rs`, inspired by
 * ajayyy/DeArrowThumbnailCache but written from scratch), with fallback proxy
 * to dearrow-thumb.ajay.app.
 */
private const val SPONSORBLOCK_MIRROR = "https://api.vayunmathur.com"
private const val DEARROW_THUMB_MIRROR = "https://api.vayunmathur.com/api/dearrow/thumbnail"

suspend fun getDeArrowBranding(videoId: Long): DeArrowBranding? {
    val idString = decodeVideoID(videoId)
    return try {
        NetworkClient.getJson<DeArrowBranding>("$SPONSORBLOCK_MIRROR/api/branding?videoID=$idString")
    } catch (e: Exception) {
        null
    }
}

fun DeArrowBranding.trustedTitle(): String? {
    val title = titles.firstOrNull() ?: return null
    if (title.original) return null
    if (!title.locked && title.votes < 0) return null
    return title.title.replace(">", "").trim()
}

fun DeArrowBranding.trustedThumbnailUrl(videoId: Long): String? {
    val thumb = thumbnails.firstOrNull() ?: return null
    if (thumb.original) return null
    if (!thumb.locked && thumb.votes < 0) return null
    val timestamp = thumb.timestamp ?: return null
    // Self-hosted renderer on api.vayunmathur.com (yt-dlp + ffmpeg), fallback proxy to ajay.app server-side
    return "$DEARROW_THUMB_MIRROR?videoID=${decodeVideoID(videoId)}&time=$timestamp"
}

suspend fun getSponsorSegments(videoId: Long): List<SponsorSegment> {
    val idString = decodeVideoID(videoId)
    return try {
        NetworkClient.getJson("$SPONSORBLOCK_MIRROR/api/skipSegments?videoID=$idString")
    } catch (e: Exception) {
        emptyList()
    }
}
