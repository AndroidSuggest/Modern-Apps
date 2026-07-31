package org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators

import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeDashManifestCreatorsUtils.BASE_URL
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeDashManifestCreatorsUtils.INITIALIZATION
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeDashManifestCreatorsUtils.MPD
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeDashManifestCreatorsUtils.REPRESENTATION
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeDashManifestCreatorsUtils.SEGMENT_BASE
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeDashManifestCreatorsUtils.buildAndCacheResult
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeDashManifestCreatorsUtils.generateDocumentAndDoCommonElementsGeneration
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeDashManifestCreatorsUtils.setAttribute
import org.schabi.newpipe.extractor.utils.ManifestCreatorCache
import org.w3c.dom.DOMException
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Class which generates DASH manifests of progressive YouTube streams.
 */
object YoutubeProgressiveDashManifestCreator {

    /**
     * Cache of DASH manifests generated for progressive streams.
     */
    private val PROGRESSIVE_STREAMS_CACHE: ManifestCreatorCache<String, String> = ManifestCreatorCache()

    @JvmStatic
    @Throws(CreationException::class)
    fun fromProgressiveStreamingUrl(
        progressiveStreamingBaseUrl: String,
        itagItem: ItagItem,
        durationSecondsFallback: Long
    ): String {
        if (PROGRESSIVE_STREAMS_CACHE.containsKey(progressiveStreamingBaseUrl)) {
            return PROGRESSIVE_STREAMS_CACHE.get(progressiveStreamingBaseUrl)!!.getSecond()
        }

        val itagItemDuration = itagItem.approxDurationMs
        val streamDuration: Long = if (itagItemDuration != -1L) {
            itagItemDuration
        } else {
            if (durationSecondsFallback > 0) {
                durationSecondsFallback * 1000
            } else {
                throw CreationException.couldNotAddElement(
                    MPD,
                    "the duration of the stream could not be determined and durationSecondsFallback is <= 0"
                )
            }
        }

        val doc = generateDocumentAndDoCommonElementsGeneration(itagItem, streamDuration)

        generateBaseUrlElement(doc, progressiveStreamingBaseUrl)
        generateSegmentBaseElement(doc, itagItem)
        generateInitializationElement(doc, itagItem)

        return buildAndCacheResult(progressiveStreamingBaseUrl, doc, PROGRESSIVE_STREAMS_CACHE)
    }

    @JvmStatic
    fun getCache(): ManifestCreatorCache<String, String> = PROGRESSIVE_STREAMS_CACHE

    @Throws(CreationException::class)
    private fun generateBaseUrlElement(doc: Document, baseUrl: String) {
        try {
            val representationElement = doc.getElementsByTagName(REPRESENTATION).item(0) as Element
            val baseURLElement = doc.createElement(BASE_URL)
            baseURLElement.textContent = baseUrl
            representationElement.appendChild(baseURLElement)
        } catch (e: DOMException) {
            throw CreationException.couldNotAddElement(BASE_URL, e)
        }
    }

    @Throws(CreationException::class)
    private fun generateSegmentBaseElement(doc: Document, itagItem: ItagItem) {
        try {
            val representationElement = doc.getElementsByTagName(REPRESENTATION).item(0) as Element
            val segmentBaseElement = doc.createElement(SEGMENT_BASE)

            val range = "${itagItem.indexStart}-${itagItem.indexEnd}"
            if (itagItem.indexStart < 0 || itagItem.indexEnd < 0) {
                throw CreationException.couldNotAddElement(
                    SEGMENT_BASE,
                    "ItagItem's indexStart or indexEnd are < 0: $range"
                )
            }
            setAttribute(segmentBaseElement, doc, "indexRange", range)

            representationElement.appendChild(segmentBaseElement)
        } catch (e: DOMException) {
            throw CreationException.couldNotAddElement(SEGMENT_BASE, e)
        }
    }

    @Throws(CreationException::class)
    private fun generateInitializationElement(doc: Document, itagItem: ItagItem) {
        try {
            val segmentBaseElement = doc.getElementsByTagName(SEGMENT_BASE).item(0) as Element
            val initializationElement = doc.createElement(INITIALIZATION)

            val range = "${itagItem.initStart}-${itagItem.initEnd}"
            if (itagItem.initStart < 0 || itagItem.initEnd < 0) {
                throw CreationException.couldNotAddElement(
                    INITIALIZATION,
                    "ItagItem's initStart and/or initEnd are/is < 0: $range"
                )
            }
            setAttribute(initializationElement, doc, "range", range)

            segmentBaseElement.appendChild(initializationElement)
        } catch (e: DOMException) {
            throw CreationException.couldNotAddElement(INITIALIZATION, e)
        }
    }
}
