package org.schabi.newpipe.extractor.services.youtube.extractors

import java.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.comments.CommentsExtractor
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.comments.CommentsInfoItemsCollector
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getJsonPostResponse
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextFromObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.prepareDesktopJsonBuilder
import org.schabi.newpipe.extractor.utils.JsonUtils
import org.schabi.newpipe.extractor.utils.Utils
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import org.schabi.newpipe.extractor.utils.orEmptyArray
import org.schabi.newpipe.extractor.utils.orEmptyObject

class YoutubeCommentsExtractor(
    service: StreamingService,
    uiHandler: ListLinkHandler
) : CommentsExtractor(service, uiHandler) {

    /**
     * Whether comments are disabled on video.
     */
    private var commentsDisabled: Boolean = false

    /**
     * The second ajax `/next` response.
     */
    private var ajaxJson: JsonObject? = null

    override fun getInitialPage(): InfoItemsPage<CommentsInfoItem> {
        if (commentsDisabled) {
            return getInfoItemsPageForDisabledComments()
        }
        return extractComments(ajaxJson!!)
    }

    /**
     * Finds the initial comments token and initializes commentsDisabled.
     *
     * Also sets [commentsDisabled].
     *
     * @return the continuation token or null if none was found
     */
    private fun findInitialCommentsToken(nextResponse: JsonObject): String? {
        val contents = getJsonContents(nextResponse)

        if (contents == null) {
            return null
        }

        val token = contents.filterIsInstance<JsonObject>()
            .filter { jObj ->
                try {
                    return@filter "comments-section" ==
                        JsonUtils.getString(jObj, "itemSectionRenderer.targetId")
                } catch (ignored: ParsingException) {
                    false
                }
            }
            .firstOrNull()
            ?.let { itemSectionRenderer ->
                try {
                    JsonUtils.getString(
                        itemSectionRenderer.getObject("itemSectionRenderer").orEmptyObject()
                            .getArray("contents").orEmptyArray().getObject(0).orEmptyObject(),
                        "continuationItemRenderer.continuationEndpoint" +
                            ".continuationCommand.token"
                    )
                } catch (ignored: ParsingException) {
                    null
                }
            }

        commentsDisabled = token == null

        return token
    }

    private fun getJsonContents(nextResponse: JsonObject): JsonArray? {
        return try {
            JsonUtils.getArray(
                nextResponse,
                "contents.twoColumnWatchNextResults.results.results.contents"
            )
        } catch (e: ParsingException) {
            null
        }
    }

    @Throws(ParsingException::class)
    private fun getMutationPayloadFromEntityKey(
        mutations: JsonArray,
        commentKey: String
    ): JsonObject {
        return mutations.filterIsInstance<JsonObject>()
            .filter { it.getString("entityKey") == commentKey }
            .firstOrNull()
            ?.getObject("payload")
            ?: throw ParsingException("Could not get comment entity payload mutation")
    }

    private fun getInfoItemsPageForDisabledComments(): InfoItemsPage<CommentsInfoItem> {
        return InfoItemsPage(emptyList(), null, emptyList())
    }

    @Throws(ExtractionException::class)
    private fun getNextPage(jsonObject: JsonObject): Page? {
        val onResponseReceivedEndpoints = jsonObject.getArray("onResponseReceivedEndpoints")
            ?: return null

        if (onResponseReceivedEndpoints.isEmpty()) {
            return null
        }

        val continuationItemsArray: JsonArray
        try {
            val endpoint = onResponseReceivedEndpoints
                .getObject(onResponseReceivedEndpoints.size - 1).orEmptyObject()
            continuationItemsArray = (endpoint.getObject("reloadContinuationItemsCommand")
                ?: endpoint.getObject("appendContinuationItemsAction"))!!
                .getArray("continuationItems").orEmptyArray()
        } catch (e: Exception) {
            return null
        }

        if (continuationItemsArray.isEmpty()) {
            return null
        }

        val continuationItemRenderer = continuationItemsArray
            .getObject(continuationItemsArray.size - 1).orEmptyObject()
            .getObject("continuationItemRenderer").orEmptyObject()

        val jsonPath = if (continuationItemRenderer.containsKey("button"))
            "button.buttonRenderer.command.continuationCommand.token"
        else
            "continuationEndpoint.continuationCommand.token"

        val continuation: String
        try {
            continuation = JsonUtils.getString(continuationItemRenderer, jsonPath)
        } catch (e: Exception) {
            return null
        }
        return getNextPage(continuation)
    }

    @Throws(ParsingException::class)
    private fun getNextPage(continuation: String): Page {
        return Page(getUrl(), continuation)
    }

    @Throws(IOException::class, ExtractionException::class)
    override fun getPage(page: Page): InfoItemsPage<CommentsInfoItem> {
        if (commentsDisabled) {
            return getInfoItemsPageForDisabledComments()
        }

        if (isNullOrEmpty(page.id)) {
            throw IllegalArgumentException("Page doesn't have the continuation.")
        }

        val localization: Localization = getExtractorLocalization()
        val body = prepareDesktopJsonBuilder(localization, getExtractorContentCountry())
            .value("continuation", page.id)
            .done().toString()
            .toByteArray(Charsets.UTF_8)

        val jsonObject = getJsonPostResponse("next", body, localization)

        return extractComments(jsonObject)
    }

    @Throws(ExtractionException::class)
    private fun extractComments(jsonObject: JsonObject): InfoItemsPage<CommentsInfoItem> {
        val collector = CommentsInfoItemsCollector(getServiceId())
        collectCommentsFrom(collector, jsonObject)
        return InfoItemsPage(collector, getNextPage(jsonObject))
    }

    @Throws(ParsingException::class)
    private fun collectCommentsFrom(
        collector: CommentsInfoItemsCollector,
        jsonObject: JsonObject
    ) {
        val onResponseReceivedEndpoints = jsonObject.getArray("onResponseReceivedEndpoints")
            ?: return

        if (onResponseReceivedEndpoints.isEmpty()) {
            return
        }
        val commentsEndpoint = onResponseReceivedEndpoints
            .getObject(onResponseReceivedEndpoints.size - 1).orEmptyObject()

        val path: String = when {
            commentsEndpoint.containsKey("reloadContinuationItemsCommand") ->
                "reloadContinuationItemsCommand.continuationItems"
            commentsEndpoint.containsKey("appendContinuationItemsAction") ->
                "appendContinuationItemsAction.continuationItems"
            else -> return
        }

        val contents: JsonArray
        try {
            // A copy of the array is needed, otherwise the continuation item is removed
            contents = JsonArray(JsonUtils.getArray(commentsEndpoint, path).toList())
        } catch (e: Exception) {
            return
        }

        val index = contents.size - 1
        val mutableContents = contents.toMutableList()
        if (mutableContents.isNotEmpty() &&
            (mutableContents[index] as? JsonObject)?.containsKey("continuationItemRenderer") == true
        ) {
            mutableContents.removeAt(index)
        }

        val mutations = jsonObject.getObject("frameworkUpdates").orEmptyObject()
            .getObject("entityBatchUpdate").orEmptyObject()
            .getArray("mutations").orEmptyArray()
        val videoUrl = getUrl()
        val timeAgoParser = getTimeAgoParser()

        for (o in mutableContents) {
            if (o !is JsonObject) continue
            collectCommentItem(mutations, o, collector, videoUrl, timeAgoParser)
        }
    }

    @Throws(ParsingException::class)
    private fun collectCommentItem(
        mutations: JsonArray,
        content: JsonObject,
        collector: CommentsInfoItemsCollector,
        videoUrl: String,
        timeAgoParser: org.schabi.newpipe.extractor.localization.TimeAgoParser
    ) {
        when {
            content.containsKey("commentThreadRenderer") -> {
                val commentThreadRenderer = content.getObject("commentThreadRenderer").orEmptyObject()
                if (commentThreadRenderer.containsKey(COMMENT_VIEW_MODEL_KEY)) {
                    val commentViewModel = commentThreadRenderer.getObject(COMMENT_VIEW_MODEL_KEY).orEmptyObject()
                        .getObject(COMMENT_VIEW_MODEL_KEY).orEmptyObject()
                    collector.commit(
                        YoutubeCommentsEUVMInfoItemExtractor(
                            commentViewModel,
                            commentThreadRenderer.getObject("replies").orEmptyObject()
                                .getObject("commentRepliesRenderer"),
                            getMutationPayloadFromEntityKey(
                                mutations,
                                commentViewModel.getString("commentKey", "")
                            ).getObject("commentEntityPayload").orEmptyObject(),
                            getMutationPayloadFromEntityKey(
                                mutations,
                                commentViewModel.getString("toolbarStateKey", "")
                            ).getObject("engagementToolbarStateEntityPayload").orEmptyObject(),
                            videoUrl,
                            timeAgoParser
                        )
                    )
                } else if (commentThreadRenderer.containsKey("comment")) {
                    collector.commit(
                        YoutubeCommentsInfoItemExtractor(
                            commentThreadRenderer.getObject("comment").orEmptyObject()
                                .getObject(COMMENT_RENDERER_KEY).orEmptyObject(),
                            commentThreadRenderer.getObject("replies").orEmptyObject()
                                .getObject("commentRepliesRenderer"),
                            videoUrl,
                            timeAgoParser
                        )
                    )
                }
            }
            content.containsKey(COMMENT_VIEW_MODEL_KEY) -> {
                val commentViewModel = content.getObject(COMMENT_VIEW_MODEL_KEY).orEmptyObject()
                collector.commit(
                    YoutubeCommentsEUVMInfoItemExtractor(
                        commentViewModel,
                        null,
                        getMutationPayloadFromEntityKey(
                            mutations,
                            commentViewModel.getString("commentKey", "")
                        ).getObject("commentEntityPayload").orEmptyObject(),
                        getMutationPayloadFromEntityKey(
                            mutations,
                            commentViewModel.getString("toolbarStateKey", "")
                        ).getObject("engagementToolbarStateEntityPayload").orEmptyObject(),
                        videoUrl,
                        timeAgoParser
                    )
                )
            }
            content.containsKey(COMMENT_RENDERER_KEY) -> {
                collector.commit(
                    YoutubeCommentsInfoItemExtractor(
                        content.getObject(COMMENT_RENDERER_KEY).orEmptyObject(),
                        null,
                        videoUrl,
                        timeAgoParser
                    )
                )
            }
        }
    }

    @Throws(IOException::class, ExtractionException::class)
    override fun onFetchPage(downloader: Downloader) {
        val localization = getExtractorLocalization()
        val body = prepareDesktopJsonBuilder(localization, getExtractorContentCountry())
            .value("videoId", getId())
            .done().toString()
            .toByteArray(Charsets.UTF_8)

        val initialToken = findInitialCommentsToken(
            getJsonPostResponse("next", body, localization)
        )

        if (initialToken == null) {
            return
        }

        val ajaxBody = prepareDesktopJsonBuilder(localization, getExtractorContentCountry())
            .value("continuation", initialToken)
            .done().toString()
            .toByteArray(Charsets.UTF_8)

        ajaxJson = getJsonPostResponse("next", ajaxBody, localization)
    }

    override fun isCommentsDisabled(): Boolean = commentsDisabled

    @Throws(ExtractionException::class)
    override fun getCommentsCount(): Int {
        assertPageFetched()

        if (commentsDisabled) {
            return -1
        }

        val countText = ajaxJson!!.getArray("onResponseReceivedEndpoints").orEmptyArray()
            .getObject(0).orEmptyObject()
            .getObject("reloadContinuationItemsCommand").orEmptyObject()
            .getArray("continuationItems").orEmptyArray()
            .getObject(0).orEmptyObject()
            .getObject("commentsHeaderRenderer").orEmptyObject()
            .getObject("countText").orEmptyObject()

        try {
            return Utils.removeNonDigitCharacters(getTextFromObject(countText)!!).toInt()
        } catch (e: Exception) {
            throw ExtractionException("Unable to get comments count", e)
        }
    }

    companion object {
        private const val COMMENT_VIEW_MODEL_KEY = "commentViewModel"
        private const val COMMENT_RENDERER_KEY = "commentRenderer"
    }
}
