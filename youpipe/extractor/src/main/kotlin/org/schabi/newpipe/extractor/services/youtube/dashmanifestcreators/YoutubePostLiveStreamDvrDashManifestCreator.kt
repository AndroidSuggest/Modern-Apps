package org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators

import org.schabi.newpipe.extractor.services.youtube.DeliveryType
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeDashManifestCreatorsUtils.ALR_YES
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeDashManifestCreatorsUtils.RN_0
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeDashManifestCreatorsUtils.SEGMENT_TIMELINE
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeDashManifestCreatorsUtils.SQ_0
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeDashManifestCreatorsUtils.buildAndCacheResult
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeDashManifestCreatorsUtils.generateDocumentAndDoCommonElementsGeneration
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeDashManifestCreatorsUtils.generateSegmentTemplateElement
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeDashManifestCreatorsUtils.generateSegmentTimelineElement
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeDashManifestCreatorsUtils.getInitializationResponse
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeDashManifestCreatorsUtils.setAttribute
import org.schabi.newpipe.extractor.utils.ManifestCreatorCache
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.w3c.dom.DOMException
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Class which generates DASH manifests of YouTube post-live DVR streams (which use the
 * LIVE delivery type).
 */
object YoutubePostLiveStreamDvrDashManifestCreator {

    /**
     * Cache of DASH manifests generated for post-live-DVR streams.
     */
    private val POST_LIVE_DVR_STREAMS_CACHE: ManifestCreatorCache<String, String> = ManifestCreatorCache()

    @JvmStatic
    @Throws(CreationException::class)
    fun fromPostLiveStreamDvrStreamingUrl(
        postLiveStreamDvrStreamingUrl: String,
        itagItem: ItagItem,
        targetDurationSec: Int,
        durationSecondsFallback: Long
    ): String {
        if (POST_LIVE_DVR_STREAMS_CACHE.containsKey(postLiveStreamDvrStreamingUrl)) {
            return POST_LIVE_DVR_STREAMS_CACHE.get(postLiveStreamDvrStreamingUrl)!!.getSecond()
        }

        var realPostLiveStreamDvrStreamingUrl = postLiveStreamDvrStreamingUrl
        val streamDurationString: String
        val segmentCount: String

        if (targetDurationSec <= 0) {
            throw CreationException("targetDurationSec value is <= 0: $targetDurationSec")
        }

        try {
            val response = getInitializationResponse(
                realPostLiveStreamDvrStreamingUrl,
                itagItem,
                DeliveryType.LIVE
            )
            realPostLiveStreamDvrStreamingUrl = response.latestUrl().replace(SQ_0, "")
                .replace(RN_0, "").replace(ALR_YES, "")

            val responseCode = response.responseCode()
            if (responseCode != 200) {
                throw CreationException(
                    "Could not get the initialization sequence: response code $responseCode"
                )
            }

            val responseHeaders = response.responseHeaders()
            streamDurationString = responseHeaders["X-Head-Time-Millis"]!![0]
            segmentCount = responseHeaders["X-Head-Seqnum"]!![0]
        } catch (e: IndexOutOfBoundsException) {
            throw CreationException(
                "Could not get the value of the X-Head-Time-Millis or the X-Head-Seqnum header",
                e
            )
        }

        if (isNullOrEmpty(segmentCount)) {
            throw CreationException("Could not get the number of segments")
        }

        val streamDuration: Long = try {
            streamDurationString.toLong()
        } catch (e: NumberFormatException) {
            durationSecondsFallback
        }

        val doc = generateDocumentAndDoCommonElementsGeneration(itagItem, streamDuration)

        generateSegmentTemplateElement(doc, realPostLiveStreamDvrStreamingUrl, DeliveryType.LIVE)
        generateSegmentTimelineElement(doc)
        generateSegmentElementForPostLiveDvrStreams(doc, targetDurationSec, segmentCount)

        return buildAndCacheResult(
            postLiveStreamDvrStreamingUrl,
            doc,
            POST_LIVE_DVR_STREAMS_CACHE
        )
    }

    @JvmStatic
    fun getCache(): ManifestCreatorCache<String, String> = POST_LIVE_DVR_STREAMS_CACHE

    @Throws(CreationException::class)
    private fun generateSegmentElementForPostLiveDvrStreams(
        doc: Document,
        targetDurationSeconds: Int,
        segmentCount: String
    ) {
        try {
            val segmentTimelineElement = doc.getElementsByTagName(SEGMENT_TIMELINE).item(0) as Element
            val sElement = doc.createElement("S")

            setAttribute(sElement, doc, "d", (targetDurationSeconds * 1000).toString())
            setAttribute(sElement, doc, "r", segmentCount)

            segmentTimelineElement.appendChild(sElement)
        } catch (e: DOMException) {
            throw CreationException.couldNotAddElement("segment (S)", e)
        }
    }
}
