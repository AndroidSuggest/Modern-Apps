package org.schabi.newpipe.extractor.services.youtube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.schabi.newpipe.extractor.MetaInfo
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.extractCachedUrlIfNeeded
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextFromObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextFromObjectOrThrow
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getUrlFromNavigationEndpoint
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isGoogleURL
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.schabi.newpipe.extractor.utils.Utils.replaceHttpWithHttps
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getObject
import java.net.MalformedURLException
import java.net.URL

object YoutubeMetaInfoHelper {

    @JvmStatic
    @Throws(ParsingException::class)
    fun getMetaInfo(contents: JsonArray): List<MetaInfo> {
        val metaInfo = ArrayList<MetaInfo>()
        for (content in contents) {
            val resultObject = content as? JsonObject ?: continue
            val itemSectionRenderer = resultObject.getObject("itemSectionRenderer") ?: continue
            for (sectionContentObject in itemSectionRenderer.getArray("contents").orEmpty()) {
                val sectionContent = sectionContentObject as? JsonObject ?: continue

                sectionContent.getObject("infoPanelContentRenderer")?.let {
                    metaInfo.add(getInfoPanelContent(it))
                }
                sectionContent.getObject("clarificationRenderer")?.let {
                    metaInfo.add(getClarificationRenderer(it))
                }
                sectionContent.getObject("emergencyOneboxRenderer")?.let {
                    getEmergencyOneboxRenderer(it) { info -> metaInfo.add(info) }
                }
            }
        }
        return metaInfo
    }

    @Throws(ParsingException::class)
    private fun getInfoPanelContent(infoPanelContentRenderer: JsonObject): MetaInfo {
        val metaInfo = MetaInfo()
        val sb = StringBuilder()
        for (paragraph in infoPanelContentRenderer.getArray("paragraphs").orEmpty()) {
            if (sb.isNotEmpty()) {
                sb.append("<br>")
            }
            sb.append(getTextFromObject(paragraph as? JsonObject))
        }
        metaInfo.content = Description(sb.toString(), Description.HTML)

        val sourceEndpoint = infoPanelContentRenderer.getObject("sourceEndpoint")
        if (sourceEndpoint != null) {
            val metaInfoLinkUrl = getUrlFromNavigationEndpoint(sourceEndpoint)
            try {
                metaInfo.addUrl(URL(requireNotNull(extractCachedUrlIfNeeded(metaInfoLinkUrl))))
            } catch (e: NullPointerException) {
                throw ParsingException("Could not get metadata info URL", e)
            } catch (e: IllegalArgumentException) {
                throw ParsingException("Could not get metadata info URL", e)
            } catch (e: MalformedURLException) {
                throw ParsingException("Could not get metadata info URL", e)
            }

            val metaInfoLinkText = getTextFromObject(
                infoPanelContentRenderer.getObject("inlineSource")
                    ?: infoPanelContentRenderer.getObject("disclaimer")
            )
            if (isNullOrEmpty(metaInfoLinkText)) {
                throw ParsingException("Could not get metadata info link text.")
            }
            metaInfo.addUrlText(metaInfoLinkText!!)
        }

        return metaInfo
    }

    @Throws(ParsingException::class)
    private fun getClarificationRenderer(clarificationRenderer: JsonObject): MetaInfo {
        val metaInfo = MetaInfo()

        val title = getTextFromObject(clarificationRenderer.getObject("contentTitle"))
        val text = getTextFromObject(clarificationRenderer.getObject("text"))
        if (title == null || text == null) {
            throw ParsingException("Could not extract clarification renderer content")
        }
        metaInfo.title = title
        metaInfo.content = Description(text, Description.PLAIN_TEXT)

        val actionButton = clarificationRenderer.getObject("actionButton")
            ?.getObject("buttonRenderer")
        if (actionButton != null) {
            try {
                val url = actionButton.getObject("command")
                    ?.let { getUrlFromNavigationEndpoint(it) }
                metaInfo.addUrl(URL(requireNotNull(extractCachedUrlIfNeeded(url))))
            } catch (e: NullPointerException) {
                throw ParsingException("Could not get metadata info URL", e)
            } catch (e: IllegalArgumentException) {
                throw ParsingException("Could not get metadata info URL", e)
            } catch (e: MalformedURLException) {
                throw ParsingException("Could not get metadata info URL", e)
            }

            val metaInfoLinkText = getTextFromObject(actionButton.getObject("text"))
            if (isNullOrEmpty(metaInfoLinkText)) {
                throw ParsingException("Could not get metadata info link text.")
            }
            metaInfo.addUrlText(metaInfoLinkText!!)
        }

        val secondaryEndpoint = clarificationRenderer.getObject("secondaryEndpoint")
        val secondarySource = clarificationRenderer.getObject("secondarySource")
        if (secondaryEndpoint != null && secondarySource != null) {
            val url = getUrlFromNavigationEndpoint(secondaryEndpoint)
            // Ignore Google URLs, because those point to a Google search about "Covid-19"
            if (url != null && !isGoogleURL(url)) {
                try {
                    metaInfo.addUrl(URL(url))
                    val description = getTextFromObject(secondarySource)
                    metaInfo.addUrlText(description ?: url)
                } catch (e: MalformedURLException) {
                    throw ParsingException("Could not get metadata info secondary URL", e)
                }
            }
        }

        return metaInfo
    }

    @Throws(ParsingException::class)
    private fun getEmergencyOneboxRenderer(
        emergencyOneboxRenderer: JsonObject,
        addMetaInfo: (MetaInfo) -> Unit
    ) {
        val supportRenderers = emergencyOneboxRenderer.values
            .filterIsInstance<JsonObject>()
            .mapNotNull { it.getObject("singleActionEmergencySupportRenderer") }

        if (supportRenderers.isEmpty()) {
            throw ParsingException("Could not extract any meta info from emergency renderer")
        }

        for (r in supportRenderers) {
            val metaInfo = MetaInfo()

            // usually an encouragement like "We are with you"
            val title = getTextFromObjectOrThrow(r.getObject("title") ?: JsonObject(emptyMap()), "title")

            // usually a phone number; this variable is expected to start with "\n"
            val actionText = r.getObject("actionText")
            val contacts = r.getArray("contacts")
            val action = when {
                actionText != null -> "\n" + getTextFromObjectOrThrow(actionText, "action")
                contacts != null -> buildString {
                    // Loop over contacts item from the first contact to the last one
                    for (i in contacts.indices) {
                        append("\n")
                        append(
                            getTextFromObjectOrThrow(
                                contacts.getObject(i)?.getObject("actionText")
                                    ?: JsonObject(emptyMap()),
                                "contacts.actionText"
                            )
                        )
                    }
                }
                else -> ""
            }

            // usually details about the phone number
            val details = getTextFromObjectOrThrow(
                r.getObject("detailsText") ?: JsonObject(emptyMap()), "details"
            )

            // usually the name of an association
            val urlText = getTextFromObjectOrThrow(
                r.getObject("navigationText") ?: JsonObject(emptyMap()), "urlText"
            )

            metaInfo.title = title
            metaInfo.content = Description(details + action, Description.PLAIN_TEXT)
            metaInfo.addUrlText(urlText)

            // usually the webpage of the association
            val url = r.getObject("navigationEndpoint")
                ?.let { getUrlFromNavigationEndpoint(it) }
                ?: throw ParsingException("Could not extract emergency renderer url")

            try {
                metaInfo.addUrl(URL(replaceHttpWithHttps(url)))
            } catch (e: MalformedURLException) {
                throw ParsingException("Could not parse emergency renderer url", e)
            }

            addMetaInfo(metaInfo)
        }
    }
}
