package org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.DeliveryType
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getAndroidUserAgent
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getIosUserAgent
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getOriginReferrerHeaders
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getVisionOsUserAgent
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isAndroidStreamingUrl
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isIosStreamingUrl
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isVisionOsStreamingUrl
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isWebEmbeddedPlayerStreamingUrl
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isWebStreamingUrl
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.utils.ManifestCreatorCache
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.w3c.dom.Attr
import org.w3c.dom.DOMException
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.StringWriter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Utilities and constants for YouTube DASH manifest creators.
 *
 * This class includes common methods of manifest creators and useful constants.
 *
 * Generation of DASH documents and their conversion as a string is done using external classes
 * from org.w3c.dom and javax.xml packages.
 */
object YoutubeDashManifestCreatorsUtils {

    /**
     * The redirect count limit that this class uses, which is the same limit as OkHttp.
     */
    const val MAXIMUM_REDIRECT_COUNT: Int = 20

    /**
     * URL parameter of the first sequence for live, post-live-DVR and OTF streams.
     */
    const val SQ_0: String = "&sq=0"

    /**
     * URL parameter of the first stream request made by official clients.
     */
    const val RN_0: String = "&rn=0"

    /**
     * URL parameter specific to web clients. When this param is added, if a redirection occurs,
     * the server will not redirect clients to the redirect URL. Instead, it will provide this URL
     * as the response body.
     */
    const val ALR_YES: String = "&alr=yes"

    const val MPD: String = "MPD"
    const val PERIOD: String = "Period"
    const val ADAPTATION_SET: String = "AdaptationSet"
    const val ROLE: String = "Role"
    const val REPRESENTATION: String = "Representation"
    const val AUDIO_CHANNEL_CONFIGURATION: String = "AudioChannelConfiguration"
    const val SEGMENT_TEMPLATE: String = "SegmentTemplate"
    const val SEGMENT_TIMELINE: String = "SegmentTimeline"
    const val BASE_URL: String = "BaseURL"
    const val SEGMENT_BASE: String = "SegmentBase"
    const val INITIALIZATION: String = "Initialization"

    /**
     * Create an attribute with Document.createAttribute, assign to it the provided
     * name and value, then add it to the provided element using Element.setAttributeNode.
     */
    @JvmStatic
    fun setAttribute(element: Element, doc: Document, name: String, value: String) {
        val attr = doc.createAttribute(name)
        attr.value = value
        element.setAttributeNode(attr)
    }

    @JvmStatic
    @Throws(CreationException::class)
    fun generateDocumentAndDoCommonElementsGeneration(
        itagItem: ItagItem,
        streamDuration: Long
    ): Document {
        val doc = generateDocumentAndMpdElement(streamDuration)
        generatePeriodElement(doc)
        generateAdaptationSetElement(doc, itagItem)
        generateRoleElement(doc, itagItem)
        generateRepresentationElement(doc, itagItem)
        if (itagItem.itagType == ItagItem.ItagType.AUDIO) {
            generateAudioChannelConfigurationElement(doc, itagItem)
        }
        return doc
    }

    @JvmStatic
    @Throws(CreationException::class)
    fun generateDocumentAndMpdElement(duration: Long): Document {
        try {
            val doc = newDocument()
            val mpdElement = doc.createElement(MPD)
            doc.appendChild(mpdElement)

            setAttribute(mpdElement, doc, "xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
            setAttribute(mpdElement, doc, "xmlns", "urn:mpeg:DASH:schema:MPD:2011")
            setAttribute(
                mpdElement, doc, "xsi:schemaLocation",
                "urn:mpeg:DASH:schema:MPD:2011 DASH-MPD.xsd"
            )
            setAttribute(mpdElement, doc, "minBufferTime", "PT1.500S")
            setAttribute(mpdElement, doc, "profiles", "urn:mpeg:dash:profile:full:2011")
            setAttribute(mpdElement, doc, "type", "static")
            setAttribute(
                mpdElement, doc, "mediaPresentationDuration",
                String.format(Locale.ENGLISH, "PT%.3fS", duration / 1000.0)
            )
            return doc
        } catch (e: Exception) {
            throw CreationException(
                "Could not generate the DASH manifest or append the MPD doc to it", e
            )
        }
    }

    @JvmStatic
    @Throws(CreationException::class)
    fun generatePeriodElement(doc: Document) {
        try {
            val mpdElement = doc.getElementsByTagName(MPD).item(0) as Element
            val periodElement = doc.createElement(PERIOD)
            mpdElement.appendChild(periodElement)
        } catch (e: DOMException) {
            throw CreationException.couldNotAddElement(PERIOD, e)
        }
    }

    @JvmStatic
    @Throws(CreationException::class)
    fun generateAdaptationSetElement(doc: Document, itagItem: ItagItem) {
        try {
            val periodElement = doc.getElementsByTagName(PERIOD).item(0) as Element
            val adaptationSetElement = doc.createElement(ADAPTATION_SET)

            setAttribute(adaptationSetElement, doc, "id", "0")

            val mediaFormat = itagItem.mediaFormat
            if (mediaFormat == null || isNullOrEmpty(mediaFormat.mimeType)) {
                throw CreationException.couldNotAddElement(
                    ADAPTATION_SET,
                    "the MediaFormat or its mime type is null or empty"
                )
            }

            if (itagItem.itagType == ItagItem.ItagType.AUDIO) {
                val audioLocale = itagItem.audioLocale
                if (audioLocale != null) {
                    val audioLanguage = audioLocale.language
                    if (!audioLanguage.isEmpty()) {
                        setAttribute(adaptationSetElement, doc, "lang", audioLanguage)
                    }
                }
            }

            setAttribute(adaptationSetElement, doc, "mimeType", mediaFormat.mimeType)
            setAttribute(adaptationSetElement, doc, "subsegmentAlignment", "true")

            periodElement.appendChild(adaptationSetElement)
        } catch (e: DOMException) {
            throw CreationException.couldNotAddElement(ADAPTATION_SET, e)
        }
    }

    @JvmStatic
    @Throws(CreationException::class)
    fun generateRoleElement(doc: Document, itagItem: ItagItem) {
        try {
            val adaptationSetElement = doc.getElementsByTagName(ADAPTATION_SET).item(0) as Element
            val roleElement = doc.createElement(ROLE)

            setAttribute(roleElement, doc, "schemeIdUri", "urn:mpeg:DASH:role:2011")
            setAttribute(roleElement, doc, "value", getRoleValue(itagItem.audioTrackType))

            adaptationSetElement.appendChild(roleElement)
        } catch (e: DOMException) {
            throw CreationException.couldNotAddElement(ROLE, e)
        }
    }

    private fun getRoleValue(trackType: AudioTrackType?): String {
        if (trackType != null) {
            return when (trackType) {
                AudioTrackType.ORIGINAL -> "main"
                AudioTrackType.DUBBED -> "dub"
                AudioTrackType.DESCRIPTIVE -> "description"
                else -> "alternate"
            }
        }
        return "main"
    }

    @JvmStatic
    @Throws(CreationException::class)
    fun generateRepresentationElement(doc: Document, itagItem: ItagItem) {
        try {
            val adaptationSetElement = doc.getElementsByTagName(ADAPTATION_SET).item(0) as Element
            val representationElement = doc.createElement(REPRESENTATION)

            val id = itagItem.id
            if (id <= 0) {
                throw CreationException.couldNotAddElement(
                    REPRESENTATION,
                    "the id of the ItagItem is <= 0"
                )
            }
            setAttribute(representationElement, doc, "id", id.toString())

            val codec = itagItem.codec
            if (isNullOrEmpty(codec)) {
                throw CreationException.couldNotAddElement(
                    ADAPTATION_SET,
                    "the codec value of the ItagItem is null or empty"
                )
            }
            setAttribute(representationElement, doc, "codecs", codec!!)
            setAttribute(representationElement, doc, "startWithSAP", "1")
            setAttribute(representationElement, doc, "maxPlayoutRate", "1")

            val bitrate = itagItem.bitrate
            if (bitrate <= 0) {
                throw CreationException.couldNotAddElement(
                    REPRESENTATION,
                    "the bitrate of the ItagItem is <= 0"
                )
            }
            setAttribute(representationElement, doc, "bandwidth", bitrate.toString())

            if (itagItem.itagType == ItagItem.ItagType.VIDEO ||
                itagItem.itagType == ItagItem.ItagType.VIDEO_ONLY
            ) {
                val height = itagItem.height
                val width = itagItem.width
                if (height <= 0 && width <= 0) {
                    throw CreationException.couldNotAddElement(
                        REPRESENTATION,
                        "both width and height of the ItagItem are <= 0"
                    )
                }
                if (width > 0) {
                    setAttribute(representationElement, doc, "width", width.toString())
                }
                setAttribute(
                    representationElement, doc, "height",
                    itagItem.height.toString()
                )

                val fps = itagItem.fps
                if (fps > 0) {
                    setAttribute(representationElement, doc, "frameRate", fps.toString())
                }
            }

            if (itagItem.itagType == ItagItem.ItagType.AUDIO && itagItem.sampleRate > 0) {
                val audioSamplingRateAttribute: Attr = doc.createAttribute("audioSamplingRate")
                audioSamplingRateAttribute.value = itagItem.sampleRate.toString()
            }

            adaptationSetElement.appendChild(representationElement)
        } catch (e: DOMException) {
            throw CreationException.couldNotAddElement(REPRESENTATION, e)
        }
    }

    @JvmStatic
    @Throws(CreationException::class)
    fun generateAudioChannelConfigurationElement(doc: Document, itagItem: ItagItem) {
        try {
            val representationElement = doc.getElementsByTagName(REPRESENTATION).item(0) as Element
            val audioChannelConfigurationElement = doc.createElement(AUDIO_CHANNEL_CONFIGURATION)

            setAttribute(
                audioChannelConfigurationElement, doc, "schemeIdUri",
                "urn:mpeg:dash:23003:3:audio_channel_configuration:2011"
            )

            if (itagItem.audioChannels <= 0) {
                throw CreationException(
                    "the number of audioChannels in the ItagItem is <= 0: " +
                            itagItem.audioChannels
                )
            }
            setAttribute(
                audioChannelConfigurationElement, doc, "value",
                itagItem.audioChannels.toString()
            )

            representationElement.appendChild(audioChannelConfigurationElement)
        } catch (e: DOMException) {
            throw CreationException.couldNotAddElement(AUDIO_CHANNEL_CONFIGURATION, e)
        }
    }

    @JvmStatic
    @Throws(CreationException::class)
    fun buildAndCacheResult(
        originalBaseStreamingUrl: String,
        doc: Document,
        manifestCreatorCache: ManifestCreatorCache<String, String>
    ): String {
        try {
            val documentXml = documentToXml(doc)
            manifestCreatorCache.put(originalBaseStreamingUrl, documentXml)
            return documentXml
        } catch (e: Exception) {
            throw CreationException(
                "Could not convert the DASH manifest generated to a string", e
            )
        }
    }

    @JvmStatic
    @Throws(CreationException::class)
    fun generateSegmentTemplateElement(
        doc: Document,
        baseUrl: String,
        deliveryType: DeliveryType
    ) {
        if (deliveryType != DeliveryType.OTF && deliveryType != DeliveryType.LIVE) {
            throw CreationException.couldNotAddElement(
                SEGMENT_TEMPLATE,
                "invalid delivery type: $deliveryType"
            )
        }

        try {
            val representationElement = doc.getElementsByTagName(REPRESENTATION).item(0) as Element
            val segmentTemplateElement = doc.createElement(SEGMENT_TEMPLATE)

            setAttribute(
                segmentTemplateElement, doc, "startNumber",
                if (deliveryType == DeliveryType.LIVE) "0" else "1"
            )
            setAttribute(segmentTemplateElement, doc, "timescale", "1000")

            if (deliveryType != DeliveryType.LIVE) {
                setAttribute(segmentTemplateElement, doc, "initialization", baseUrl + SQ_0)
            }

            setAttribute(segmentTemplateElement, doc, "media", baseUrl + "&sq=\$Number\$")

            representationElement.appendChild(segmentTemplateElement)
        } catch (e: DOMException) {
            throw CreationException.couldNotAddElement(SEGMENT_TEMPLATE, e)
        }
    }

    @JvmStatic
    @Throws(CreationException::class)
    fun generateSegmentTimelineElement(doc: Document) {
        try {
            val segmentTemplateElement = doc.getElementsByTagName(SEGMENT_TEMPLATE).item(0) as Element
            val segmentTimelineElement = doc.createElement(SEGMENT_TIMELINE)
            segmentTemplateElement.appendChild(segmentTimelineElement)
        } catch (e: DOMException) {
            throw CreationException.couldNotAddElement(SEGMENT_TIMELINE, e)
        }
    }

    @JvmStatic
    @Throws(CreationException::class)
    fun getInitializationResponse(
        baseStreamingUrlParam: String,
        itagItem: ItagItem,
        deliveryType: DeliveryType
    ): Response {
        var baseStreamingUrl = baseStreamingUrlParam
        val isHtml5StreamingUrl = isWebStreamingUrl(baseStreamingUrl) ||
                isWebEmbeddedPlayerStreamingUrl(baseStreamingUrl)
        if (isHtml5StreamingUrl) {
            baseStreamingUrl += ALR_YES
        }
        baseStreamingUrl = appendRnSqParamsIfNeeded(baseStreamingUrl, deliveryType)

        val downloader = NewPipe.getDownloader()
        if (isHtml5StreamingUrl) {
            val mimeTypeExpected = itagItem.mediaFormat?.mimeType
            if (!isNullOrEmpty(mimeTypeExpected)) {
                return getStreamingWebUrlWithoutRedirects(downloader, baseStreamingUrl, mimeTypeExpected!!)
            }
        } else if (isAndroidStreamingUrl(baseStreamingUrl)) {
            try {
                return downloader.post(
                    baseStreamingUrl,
                    mapOf("User-Agent" to listOf(getAndroidUserAgent(null))),
                    "".toByteArray(Charsets.UTF_8)
                )
            } catch (e: Exception) {
                // Preserve original exception types for message
                throw CreationException("Could not get the ANDROID streaming URL response", e as? Exception ?: Exception(e))
            }
        } else if (isIosStreamingUrl(baseStreamingUrl)) {
            try {
                return downloader.post(
                    baseStreamingUrl,
                    mapOf("User-Agent" to listOf(getIosUserAgent(null))),
                    "".toByteArray(Charsets.UTF_8)
                )
            } catch (e: Exception) {
                throw CreationException("Could not get the IOS streaming URL response", e as? Exception ?: Exception(e))
            }
        } else if (isVisionOsStreamingUrl(baseStreamingUrl)) {
            try {
                return downloader.post(
                    baseStreamingUrl,
                    mapOf("User-Agent" to listOf(getVisionOsUserAgent(null))),
                    "".toByteArray(Charsets.UTF_8)
                )
            } catch (e: Exception) {
                throw CreationException("Could not get the VISIONOS streaming URL response", e as? Exception ?: Exception(e))
            }
        }

        try {
            return downloader.get(baseStreamingUrl)
        } catch (e: Exception) {
            throw CreationException("Could not get the streaming URL response", e as? Exception ?: Exception(e))
        }
    }

    private fun newDocument(): Document {
        val documentBuilderFactory = DocumentBuilderFactory.newInstance()
        try {
            documentBuilderFactory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            documentBuilderFactory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        } catch (ignored: Exception) {
        }
        return documentBuilderFactory.newDocumentBuilder().newDocument()
    }

    @Throws(javax.xml.transform.TransformerException::class)
    private fun documentToXml(doc: Document): String {
        val transformerFactory = TransformerFactory.newInstance()
        try {
            transformerFactory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            transformerFactory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        } catch (ignored: Exception) {
        }

        val transformer = transformerFactory.newTransformer()
        transformer.setOutputProperty(OutputKeys.VERSION, "1.0")
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        transformer.setOutputProperty(OutputKeys.STANDALONE, "no")

        val result = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(result))

        return result.toString()
    }

    private fun appendRnSqParamsIfNeeded(
        baseStreamingUrl: String,
        deliveryType: DeliveryType
    ): String {
        return baseStreamingUrl + (if (deliveryType == DeliveryType.PROGRESSIVE) "" else SQ_0) + RN_0
    }

    @Throws(CreationException::class)
    private fun getStreamingWebUrlWithoutRedirects(
        downloader: Downloader,
        streamingUrlParam: String,
        responseMimeTypeExpected: String
    ): Response {
        try {
            var streamingUrl = streamingUrlParam
            val headers = HashMap(getOriginReferrerHeaders("https://www.youtube.com"))

            var responseMimeType = ""
            var redirectsCount = 0
            while (responseMimeType != responseMimeTypeExpected && redirectsCount < MAXIMUM_REDIRECT_COUNT) {
                val html5Body = byteArrayOf(0x78, 0)
                val response = downloader.post(streamingUrl, headers, html5Body)

                val responseCode = response.responseCode()
                if (responseCode != 200) {
                    throw CreationException(
                        "Could not get the initialization URL: HTTP response code $responseCode"
                    )
                }

                responseMimeType = response.getHeader("Content-Type")
                    ?: throw NullPointerException(
                        "Could not get the Content-Type header from the response headers"
                    )

                if (responseMimeType == "text/plain") {
                    streamingUrl = response.responseBody()
                    redirectsCount++
                } else {
                    return response
                }
            }

            if (redirectsCount >= MAXIMUM_REDIRECT_COUNT) {
                throw CreationException(
                    "Too many redirects when trying to get the the streaming URL response of a HTML5 client"
                )
            }

            throw CreationException(
                "Could not get the streaming URL response of a HTML5 client: unreachable code reached!"
            )
        } catch (e: CreationException) {
            throw e
        } catch (e: Exception) {
            throw CreationException(
                "Could not get the streaming URL response of a HTML5 client",
                e as? Exception ?: Exception(e)
            )
        }
    }
}
