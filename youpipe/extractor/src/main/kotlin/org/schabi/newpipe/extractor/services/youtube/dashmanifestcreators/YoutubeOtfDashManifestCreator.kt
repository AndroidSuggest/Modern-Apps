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
import org.schabi.newpipe.extractor.utils.Utils
import org.w3c.dom.DOMException
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.util.Arrays

/**
 * Class which generates DASH manifests of YouTube OTF streams.
 */
object YoutubeOtfDashManifestCreator {

    /**
     * Cache of DASH manifests generated for OTF streams.
     */
    private val OTF_STREAMS_CACHE: ManifestCreatorCache<String, String> = ManifestCreatorCache()

    /**
     * Create DASH manifests from a YouTube OTF stream.
     *
     * OTF streams are YouTube-DASH specific streams which work with sequences and without the need
     * to get a manifest (even if one is provided, it is not used by official clients).
     *
     * They can be found only on videos; mostly those with a small amount of views, or ended
     * livestreams which have just been re-encoded as normal videos.
     */
    @JvmStatic
    @Throws(CreationException::class)
    fun fromOtfStreamingUrl(
        otfBaseStreamingUrl: String,
        itagItem: ItagItem,
        durationSecondsFallback: Long
    ): String {
        if (OTF_STREAMS_CACHE.containsKey(otfBaseStreamingUrl)) {
            return OTF_STREAMS_CACHE.get(otfBaseStreamingUrl)!!.getSecond()
        }

        var realOtfBaseStreamingUrl = otfBaseStreamingUrl
        val response = getInitializationResponse(realOtfBaseStreamingUrl, itagItem, DeliveryType.OTF)
        realOtfBaseStreamingUrl = response.latestUrl().replace(SQ_0, "")
            .replace(RN_0, "").replace(ALR_YES, "")

        val responseCode = response.responseCode()
        if (responseCode != 200) {
            throw CreationException("Could not get the initialization URL: response code $responseCode")
        }

        val segmentDuration: Array<String>
        try {
            val segmentsAndDurationsResponseSplit = response.responseBody()
                .split("Segment-Durations-Ms: ")[1]
                .split("\n")[0]
                .split(",")

            val segmentsArray = segmentsAndDurationsResponseSplit.toTypedArray()
            val lastIndex = segmentsArray.size - 1
            segmentDuration = if (Utils.isBlank(segmentsArray[lastIndex])) {
                Arrays.copyOf(segmentsArray, lastIndex)
            } else {
                segmentsArray
            }
        } catch (e: Exception) {
            throw CreationException("Could not get segment durations", e as? Exception ?: Exception(e))
        }

        val streamDuration: Long = try {
            getStreamDuration(segmentDuration)
        } catch (e: CreationException) {
            durationSecondsFallback * 1000
        }

        val doc = generateDocumentAndDoCommonElementsGeneration(itagItem, streamDuration)

        generateSegmentTemplateElement(doc, realOtfBaseStreamingUrl, DeliveryType.OTF)
        generateSegmentTimelineElement(doc)
        generateSegmentElementsForOtfStreams(segmentDuration, doc)

        return buildAndCacheResult(otfBaseStreamingUrl, doc, OTF_STREAMS_CACHE)
    }

    @JvmStatic
    fun getCache(): ManifestCreatorCache<String, String> = OTF_STREAMS_CACHE

    @Throws(CreationException::class)
    private fun generateSegmentElementsForOtfStreams(
        segmentDurations: Array<String>,
        doc: Document
    ) {
        try {
            val segmentTimelineElement = doc.getElementsByTagName(SEGMENT_TIMELINE).item(0) as Element

            for (segmentDuration in segmentDurations) {
                val sElement = doc.createElement("S")

                val segmentLengthRepeat = segmentDuration.split("\\(r=".toRegex()).toTypedArray()
                Integer.parseInt(segmentLengthRepeat[0])

                if (segmentLengthRepeat.size > 1) {
                    val segmentRepeatCount = Integer.parseInt(
                        Utils.removeNonDigitCharacters(segmentLengthRepeat[1])
                    )
                    setAttribute(sElement, doc, "r", segmentRepeatCount.toString())
                }
                setAttribute(sElement, doc, "d", segmentLengthRepeat[0])

                segmentTimelineElement.appendChild(sElement)
            }
        } catch (e: Exception) {
            when (e) {
                is DOMException, is IllegalStateException, is IndexOutOfBoundsException,
                is NumberFormatException -> throw CreationException.couldNotAddElement("segment (S)", e as Exception)
                else -> throw e
            }
        }
    }

    @Throws(CreationException::class)
    private fun getStreamDuration(segmentDuration: Array<String>): Long {
        try {
            var streamLengthMs: Long = 0
            for (segDuration in segmentDuration) {
                val segmentLengthRepeat = segDuration.split("\\(r=".toRegex()).toTypedArray()
                var segmentRepeatCount: Long = 0

                if (segmentLengthRepeat.size > 1) {
                    segmentRepeatCount = Utils.removeNonDigitCharacters(segmentLengthRepeat[1])
                        .toLong()
                }

                val segmentLength = segmentLengthRepeat[0].toInt()
                streamLengthMs += segmentLength + segmentRepeatCount * segmentLength
            }
            return streamLengthMs
        } catch (e: NumberFormatException) {
            throw CreationException("Could not get stream length from sequences list", e)
        }
    }
}
