package org.schabi.newpipe.extractor.services.youtube.sabr

import kotlinx.serialization.json.JsonObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeApiDecoder
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.services.youtube.YoutubeJsonBuilder
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.utils.JsonUtils
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

object YoutubeSabrProbe {
    private const val PLAYER = "player"
    private const val STREAMING_DATA = "streamingData"
    private const val ADAPTIVE_FORMATS = "adaptiveFormats"

    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class, ExtractionException::class)
    fun fetchSabrInfo(
        videoId: String,
        profile: YoutubeSabrClientProfile,
        localization: Localization,
        contentCountry: ContentCountry,
        playerPoToken: String? = null,
        visitorDataOverride: String? = null
    ): YoutubeSabrInfo {
        val playerIdentity = resolvePlayerIdentity(
            profile, localization, contentCountry, playerPoToken, visitorDataOverride
        )
        val cpn = YoutubeParsingHelper.generateContentPlaybackNonce()
        val playerResponse = fetchPlayerResponse(
            videoId, profile, localization, contentCountry, cpn,
            playerIdentity.playerPoToken, playerIdentity.visitorData
        )
        return fromPlayerResponse(
            videoId, profile, cpn, playerResponse, playerIdentity.visitorData
        )
    }

    @JvmStatic
    fun resolvePlayerIdentity(
        profile: YoutubeSabrClientProfile,
        localization: Localization,
        contentCountry: ContentCountry,
        playerPoToken: String?,
        visitorDataOverride: String?
    ): PlayerIdentityPair {
        val hasExplicitPoToken = !playerPoToken.isNullOrEmpty()
        val hasExplicitVisitorData = !visitorDataOverride.isNullOrEmpty()
        require(hasExplicitPoToken == hasExplicitVisitorData) {
            "playerPoToken and visitorDataOverride must be provided together"
        }
        if (hasExplicitPoToken) {
            return PlayerIdentityPair(playerPoToken, visitorDataOverride)
        }

        val automaticToken = YoutubeParsingHelper.getSessionPoToken(
            profile.clientName, localization, contentCountry
        )
        return if (automaticToken == null) {
            PlayerIdentityPair(playerPoToken, visitorDataOverride)
        } else {
            PlayerIdentityPair(automaticToken.getPoToken(), automaticToken.visitorData)
        }
    }

    class PlayerIdentityPair internal constructor(
        @JvmField val playerPoToken: String?,
        @JvmField val visitorData: String?
    )

    @JvmStatic
    @JvmOverloads
    @Throws(ExtractionException::class)
    fun fromPlayerResponse(
        videoId: String,
        profile: YoutubeSabrClientProfile,
        cpn: String,
        playerResponse: JsonObject,
        visitorDataOverride: String? = null
    ): YoutubeSabrInfo {
        val streamingData = playerResponse.getObject(STREAMING_DATA)
            ?: throw SabrProtocolException("Player response has no streamingData for $profile")

        val unresolvedServerAbrStreamingUrl = streamingData.getString("serverAbrStreamingUrl")
        val ustreamerConfig = extractVideoPlaybackUstreamerConfig(playerResponse)
        val visitorData = if (visitorDataOverride.isNullOrEmpty()) {
            extractVisitorData(playerResponse)
        } else {
            visitorDataOverride
        }
        val adaptiveFormats = streamingData.getArray(ADAPTIVE_FORMATS)
        val formats = YoutubeSabrFormat.parseAdaptiveFormats(adaptiveFormats)
        val signatures = LinkedHashSet<String>()
        val nParameters = LinkedHashSet<String>()
        YoutubeSabrFormat.collectDecodeParameters(formats, signatures, nParameters)
        YoutubeSabrFormat.extractNParameter(unresolvedServerAbrStreamingUrl)?.let {
            nParameters.add(it)
        }
        var serverAbrStreamingUrl = unresolvedServerAbrStreamingUrl
        if (signatures.isNotEmpty() || nParameters.isNotEmpty()) {
            val decoded: YoutubeApiDecoder.BatchDecodeResult =
                YoutubeJavaScriptPlayerManager.deobfuscateBatch(
                    videoId, ArrayList(signatures), ArrayList(nParameters)
                )
            YoutubeSabrFormat.resolveInitializationUrls(formats, decoded)
            serverAbrStreamingUrl = YoutubeSabrFormat.resolveNParameter(
                unresolvedServerAbrStreamingUrl, decoded
            )
        }

        return YoutubeSabrInfo(
            profile, videoId, cpn, resolveClientVersion(profile),
            visitorData, serverAbrStreamingUrl, ustreamerConfig, formats
        )
    }

    @JvmStatic
    @Throws(IOException::class, ExtractionException::class)
    fun probeFirstMediaResponse(
        videoId: String,
        profile: YoutubeSabrClientProfile,
        localization: Localization,
        contentCountry: ContentCountry
    ): YoutubeSabrProbeResult {
        val info = fetchSabrInfo(videoId, profile, localization, contentCountry)
        return probeFirstMediaResponse(info, localization)
    }

    @JvmStatic
    @Throws(IOException::class, ExtractionException::class)
    fun probeFirstMediaResponse(
        info: YoutubeSabrInfo,
        localization: Localization
    ): YoutubeSabrProbeResult {
        val audioFormat = info.findBestAudioFormat()
        val videoFormat = info.findLowestVideoFormat()
        if (audioFormat == null || videoFormat == null) {
            throw SabrProtocolException("Could not select audio/video SABR formats")
        }
        if (info.serverAbrStreamingUrl.isNullOrEmpty()) {
            throw SabrProtocolException("Missing serverAbrStreamingUrl")
        }

        val requestBody = YoutubeSabrRequestBuilder.buildFirstMediaRequest(
            info, audioFormat, videoFormat
        )
        return postMediaRequest(info, requestBody, 0, localization)
    }

    @JvmStatic
    @Throws(IOException::class, ExtractionException::class)
    fun probeFirstMediaResponse(
        info: YoutubeSabrInfo,
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat,
        localization: Localization
    ): YoutubeSabrProbeResult =
        probeFirstMediaResponse(info, audioFormat, videoFormat, null, localization)

    @JvmStatic
    @Throws(IOException::class, ExtractionException::class)
    fun probeFirstMediaResponse(
        info: YoutubeSabrInfo,
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat,
        streamState: YoutubeSabrStreamState?,
        localization: Localization
    ): YoutubeSabrProbeResult =
        probeFirstMediaResponse(info, audioFormat, videoFormat, streamState, null, localization)

    @Throws(IOException::class, ExtractionException::class)
    internal fun probeFirstMediaResponse(
        info: YoutubeSabrInfo,
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat,
        streamState: YoutubeSabrStreamState?,
        serverAbrStreamingUrlOverride: String?,
        localization: Localization
    ): YoutubeSabrProbeResult {
        val requestBody = YoutubeSabrRequestBuilder.buildFirstMediaRequest(
            info, audioFormat, videoFormat, streamState
        )
        return postMediaRequest(
            info, requestBody, 0, serverAbrStreamingUrlOverride, localization
        )
    }

    @Throws(IOException::class, ExtractionException::class)
    internal fun probeFirstMediaResponseStreaming(
        info: YoutubeSabrInfo,
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat,
        streamState: YoutubeSabrStreamState?,
        serverAbrStreamingUrlOverride: String?,
        segmentConsumer: SabrStreamingResponseReader.SegmentConsumer,
        localization: Localization
    ): YoutubeSabrProbeResult {
        val requestBody = YoutubeSabrRequestBuilder.buildFirstMediaRequest(
            info, audioFormat, videoFormat, streamState
        )
        return postMediaRequest(
            info, requestBody, 0, serverAbrStreamingUrlOverride,
            keepStreaming(segmentConsumer), localization
        )
    }

    @Throws(IOException::class, ExtractionException::class)
    internal fun probeFirstMediaResponseStreamingUntil(
        info: YoutubeSabrInfo,
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat,
        streamState: YoutubeSabrStreamState?,
        serverAbrStreamingUrlOverride: String?,
        segmentConsumer: SabrStreamingResponseReader.StoppableSegmentConsumer,
        localization: Localization
    ): YoutubeSabrProbeResult = probeFirstMediaResponseStreamingUntil(
        info, audioFormat, videoFormat, streamState, serverAbrStreamingUrlOverride,
        segmentConsumer, null, localization
    )

    @Throws(IOException::class, ExtractionException::class)
    internal fun probeFirstMediaResponseStreamingUntil(
        info: YoutubeSabrInfo,
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat,
        streamState: YoutubeSabrStreamState?,
        serverAbrStreamingUrlOverride: String?,
        segmentConsumer: SabrStreamingResponseReader.StoppableSegmentConsumer,
        segmentSpoolDirectory: File?,
        localization: Localization
    ): YoutubeSabrProbeResult {
        val requestBody = YoutubeSabrRequestBuilder.buildFirstMediaRequest(
            info, audioFormat, videoFormat, streamState
        )
        return postMediaRequest(
            info, requestBody, 0, serverAbrStreamingUrlOverride, segmentConsumer,
            segmentSpoolDirectory, localization
        )
    }

    @Throws(IOException::class, ExtractionException::class)
    internal fun probeFirstMediaResponseStreamingUntil(
        info: YoutubeSabrInfo,
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat,
        streamState: YoutubeSabrStreamState?,
        serverAbrStreamingUrlOverride: String?,
        segmentConsumer: SabrStreamingResponseReader.StoppableSegmentConsumer,
        segmentStartConsumer: SabrStreamingResponseReader.SegmentConsumer,
        segmentSpoolDirectory: File?,
        localization: Localization
    ): YoutubeSabrProbeResult {
        val requestBody = YoutubeSabrRequestBuilder.buildFirstMediaRequest(
            info, audioFormat, videoFormat, streamState
        )
        return postMediaRequest(
            info, requestBody, 0, serverAbrStreamingUrlOverride, segmentConsumer,
            segmentStartConsumer, segmentSpoolDirectory, localization
        )
    }

    @JvmStatic
    @Throws(IOException::class, ExtractionException::class)
    fun probeFollowUpMediaResponse(
        info: YoutubeSabrInfo,
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat,
        streamState: YoutubeSabrStreamState,
        requestNumber: Int,
        localization: Localization
    ): YoutubeSabrProbeResult {
        if (requestNumber <= 0) {
            throw SabrProtocolException("Follow-up request number must be positive")
        }
        val requestBody = YoutubeSabrRequestBuilder.buildFollowUpMediaRequest(
            info, audioFormat, videoFormat, streamState
        )
        return postMediaRequest(info, requestBody, requestNumber, localization)
    }

    @Throws(IOException::class, ExtractionException::class)
    internal fun probeFollowUpMediaResponse(
        info: YoutubeSabrInfo,
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat,
        streamState: YoutubeSabrStreamState,
        requestNumber: Int,
        serverAbrStreamingUrlOverride: String?,
        localization: Localization
    ): YoutubeSabrProbeResult {
        if (requestNumber <= 0) {
            throw SabrProtocolException("Follow-up request number must be positive")
        }
        val requestBody = YoutubeSabrRequestBuilder.buildFollowUpMediaRequest(
            info, audioFormat, videoFormat, streamState
        )
        return postMediaRequest(
            info, requestBody, requestNumber, serverAbrStreamingUrlOverride, localization
        )
    }

    @Throws(IOException::class, ExtractionException::class)
    internal fun probeFollowUpMediaResponseStreaming(
        info: YoutubeSabrInfo,
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat,
        streamState: YoutubeSabrStreamState,
        requestNumber: Int,
        serverAbrStreamingUrlOverride: String?,
        segmentConsumer: SabrStreamingResponseReader.SegmentConsumer,
        localization: Localization
    ): YoutubeSabrProbeResult {
        if (requestNumber <= 0) {
            throw SabrProtocolException("Follow-up request number must be positive")
        }
        val requestBody = YoutubeSabrRequestBuilder.buildFollowUpMediaRequest(
            info, audioFormat, videoFormat, streamState
        )
        return postMediaRequest(
            info, requestBody, requestNumber, serverAbrStreamingUrlOverride,
            keepStreaming(segmentConsumer), localization
        )
    }

    @Throws(IOException::class, ExtractionException::class)
    internal fun probeFollowUpMediaResponseStreamingUntil(
        info: YoutubeSabrInfo,
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat,
        streamState: YoutubeSabrStreamState,
        requestNumber: Int,
        serverAbrStreamingUrlOverride: String?,
        segmentConsumer: SabrStreamingResponseReader.StoppableSegmentConsumer,
        localization: Localization
    ): YoutubeSabrProbeResult = probeFollowUpMediaResponseStreamingUntil(
        info, audioFormat, videoFormat, streamState, requestNumber,
        serverAbrStreamingUrlOverride, segmentConsumer, null, localization
    )

    @Throws(IOException::class, ExtractionException::class)
    internal fun probeFollowUpMediaResponseStreamingUntil(
        info: YoutubeSabrInfo,
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat,
        streamState: YoutubeSabrStreamState,
        requestNumber: Int,
        serverAbrStreamingUrlOverride: String?,
        segmentConsumer: SabrStreamingResponseReader.StoppableSegmentConsumer,
        segmentSpoolDirectory: File?,
        localization: Localization
    ): YoutubeSabrProbeResult {
        if (requestNumber <= 0) {
            throw SabrProtocolException("Follow-up request number must be positive")
        }
        val requestBody = YoutubeSabrRequestBuilder.buildFollowUpMediaRequest(
            info, audioFormat, videoFormat, streamState
        )
        return postMediaRequest(
            info, requestBody, requestNumber, serverAbrStreamingUrlOverride, segmentConsumer,
            segmentSpoolDirectory, localization
        )
    }

    @Throws(IOException::class, ExtractionException::class)
    internal fun probeFollowUpMediaResponseStreamingUntil(
        info: YoutubeSabrInfo,
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat,
        streamState: YoutubeSabrStreamState,
        requestNumber: Int,
        serverAbrStreamingUrlOverride: String?,
        segmentConsumer: SabrStreamingResponseReader.StoppableSegmentConsumer,
        segmentStartConsumer: SabrStreamingResponseReader.SegmentConsumer,
        segmentSpoolDirectory: File?,
        localization: Localization
    ): YoutubeSabrProbeResult {
        if (requestNumber <= 0) {
            throw SabrProtocolException("Follow-up request number must be positive")
        }
        val requestBody = YoutubeSabrRequestBuilder.buildFollowUpMediaRequest(
            info, audioFormat, videoFormat, streamState
        )
        return postMediaRequest(
            info, requestBody, requestNumber, serverAbrStreamingUrlOverride, segmentConsumer,
            segmentStartConsumer, segmentSpoolDirectory, localization
        )
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun postMediaRequest(
        info: YoutubeSabrInfo,
        requestBody: ByteArray,
        requestNumber: Int,
        localization: Localization
    ): YoutubeSabrProbeResult =
        postMediaRequest(info, requestBody, requestNumber, null, null, localization)

    @Throws(IOException::class, ExtractionException::class)
    private fun postMediaRequest(
        info: YoutubeSabrInfo,
        requestBody: ByteArray,
        requestNumber: Int,
        serverAbrStreamingUrlOverride: String?,
        localization: Localization
    ): YoutubeSabrProbeResult = postMediaRequest(
        info, requestBody, requestNumber, serverAbrStreamingUrlOverride, null, localization
    )

    @Throws(IOException::class, ExtractionException::class)
    internal fun postMediaRequest(
        info: YoutubeSabrInfo,
        requestBody: ByteArray,
        requestNumber: Int,
        serverAbrStreamingUrlOverride: String?,
        segmentConsumer: SabrStreamingResponseReader.StoppableSegmentConsumer?,
        localization: Localization
    ): YoutubeSabrProbeResult = postMediaRequest(
        info, requestBody, requestNumber, serverAbrStreamingUrlOverride, segmentConsumer,
        null, localization
    )

    @Throws(IOException::class, ExtractionException::class)
    internal fun postMediaRequest(
        info: YoutubeSabrInfo,
        requestBody: ByteArray,
        requestNumber: Int,
        serverAbrStreamingUrlOverride: String?,
        segmentConsumer: SabrStreamingResponseReader.StoppableSegmentConsumer?,
        segmentSpoolDirectory: File?,
        localization: Localization
    ): YoutubeSabrProbeResult = postMediaRequest(
        info, requestBody, requestNumber, serverAbrStreamingUrlOverride, segmentConsumer,
        null, segmentSpoolDirectory, localization
    )

    @Throws(IOException::class, ExtractionException::class)
    internal fun postMediaRequest(
        info: YoutubeSabrInfo,
        requestBody: ByteArray,
        requestNumber: Int,
        serverAbrStreamingUrlOverride: String?,
        segmentConsumer: SabrStreamingResponseReader.StoppableSegmentConsumer?,
        segmentStartConsumer: SabrStreamingResponseReader.SegmentConsumer?,
        segmentSpoolDirectory: File?,
        localization: Localization
    ): YoutubeSabrProbeResult = postMediaRequest(
        info, requestBody, requestNumber, serverAbrStreamingUrlOverride, segmentConsumer,
        segmentStartConsumer, segmentSpoolDirectory, localization, SabrMediaProtocol.builtin()
    )

    @Throws(IOException::class, ExtractionException::class)
    internal fun postMediaRequest(
        info: YoutubeSabrInfo,
        requestBody: ByteArray,
        requestNumber: Int,
        serverAbrStreamingUrlOverride: String?,
        segmentConsumer: SabrStreamingResponseReader.StoppableSegmentConsumer?,
        segmentStartConsumer: SabrStreamingResponseReader.SegmentConsumer?,
        segmentSpoolDirectory: File?,
        localization: Localization,
        mediaProtocol: SabrMediaProtocol
    ): YoutubeSabrProbeResult {
        val serverAbrStreamingUrl = if (serverAbrStreamingUrlOverride.isNullOrEmpty()) {
            info.serverAbrStreamingUrl
        } else {
            serverAbrStreamingUrlOverride
        }
        if (serverAbrStreamingUrl.isNullOrEmpty()) {
            throw SabrProtocolException("Missing serverAbrStreamingUrl")
        }

        // Stream the response instead of buffering the whole body: a 4K media batch can be
        // 50-150MB, and reading it into one byte[] (+ the parts copy) OOM'd the 512MB heap. The
        // streaming reader parses parts one at a time and assembles segments on the fly.
        val requestStartNs = System.nanoTime()
        val firstSegmentElapsedMs = longArrayOf(-1)
        val timedConsumer = segmentConsumer?.let { consumer ->
            SabrStreamingResponseReader.StoppableSegmentConsumer { segment ->
                if (firstSegmentElapsedMs[0] < 0) {
                    firstSegmentElapsedMs[0] = elapsedMs(requestStartNs)
                }
                consumer.accept(segment)
            }
        }
        val timedStartConsumer = segmentStartConsumer?.let { consumer ->
            SabrStreamingResponseReader.SegmentConsumer { segment ->
                if (firstSegmentElapsedMs[0] < 0) {
                    firstSegmentElapsedMs[0] = elapsedMs(requestStartNs)
                }
                consumer.accept(segment)
            }
        }
        NewPipe.getDownloader().postStreaming(
            withSabrSessionParameters(serverAbrStreamingUrl, info.cpn, requestNumber),
            buildSabrHeaders(info), requestBody, localization
        ).use { response ->
            val contentType = response.getHeader("Content-Type")
            if (contentType == null ||
                !contentType.lowercase().contains("application/vnd.yt-ump")
            ) {
                throw SabrProtocolException(
                    "Expected UMP response, got content type: $contentType, " +
                        "status=${response.responseCode()}"
                )
            }
            val body = CountingInputStream(response.body())
            val streamed = if (timedConsumer == null && timedStartConsumer == null) {
                SabrStreamingResponseReader.readUntil(body, null, null, null, mediaProtocol)
            } else {
                SabrStreamingResponseReader.readUntil(
                    body, timedConsumer, timedStartConsumer, segmentSpoolDirectory, mediaProtocol
                )
            }
            val requestElapsedMs = elapsedMs(requestStartNs)
            return YoutubeSabrProbeResult(
                info, streamed.decodedResponse, streamed.segments, streamed.segmentCount,
                response.responseCode(), contentType, body.count, streamed.mediaPayloadBytes,
                streamed.mediaPartPayloadBytes, streamed.controlPayloadBytes,
                streamed.totalPayloadBytes, streamed.maxPartBytes,
                streamed.maxMediaPartPayloadBytes, streamed.getMaxSegmentBytes(),
                requestElapsedMs, firstSegmentElapsedMs[0]
            )
        }
    }

    private fun elapsedMs(startNs: Long): Long =
        maxOf(0L, (System.nanoTime() - startNs) / 1_000_000L)

    private fun keepStreaming(
        consumer: SabrStreamingResponseReader.SegmentConsumer
    ): SabrStreamingResponseReader.StoppableSegmentConsumer =
        SabrStreamingResponseReader.StoppableSegmentConsumer { segment ->
            consumer.accept(segment)
            true
        }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count: Long = 0
            private set

        @Throws(IOException::class)
        override fun read(): Int {
            val value = super.read()
            if (value >= 0) {
                count++
            }
            return value
        }

        @Throws(IOException::class)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) {
                count += read
            }
            return read
        }
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun fetchPlayerResponse(
        videoId: String,
        profile: YoutubeSabrClientProfile,
        localization: Localization,
        contentCountry: ContentCountry,
        cpn: String,
        playerPoToken: String?,
        visitorDataOverride: String?
    ): JsonObject {
        val body = createPlayerBody(
            videoId, profile, localization, contentCountry, cpn, playerPoToken,
            visitorDataOverride
        )
        val url = getInnertubeBaseUrl(profile) + PLAYER + "?" +
            YoutubeParsingHelper.DISABLE_PRETTY_PRINT_PARAMETER
        val response = NewPipe.getDownloader().post(
            url, buildPlayerHeaders(profile, visitorDataOverride), body, localization
        )
        return JsonUtils.toJsonObject(YoutubeParsingHelper.getValidJsonResponseBody(response))
    }

    @Throws(ParsingException::class)
    private fun createPlayerBody(
        videoId: String,
        profile: YoutubeSabrClientProfile,
        localization: Localization,
        contentCountry: ContentCountry,
        cpn: String,
        playerPoToken: String?,
        visitorDataOverride: String?
    ): ByteArray {
        val builder = YoutubeJsonBuilder()
            .`object`("context")
            .`object`("client")
            .value("clientName", profile.clientName)
            .value("clientVersion", resolveClientVersion(profile))
            .value("hl", localization.getLocalizationCode())
            .value("gl", contentCountry.countryCode)
            .value("utcOffsetMinutes", 0)

        if (!visitorDataOverride.isNullOrEmpty()) {
            builder.value("visitorData", visitorDataOverride)
        }

        when (profile) {
            YoutubeSabrClientProfile.WEB -> builder.value("platform", "DESKTOP")
            YoutubeSabrClientProfile.TVHTML5 -> builder.value("platform", "GAME_CONSOLE")
            else -> builder.value("platform", "MOBILE")
        }
        profile.osName?.let { builder.value("osName", it) }
        profile.osVersion?.let { builder.value("osVersion", it) }
        if (profile == YoutubeSabrClientProfile.MWEB) {
            profile.getUserAgent()?.let { builder.value("userAgent", it) }
        }
        when (profile) {
            YoutubeSabrClientProfile.ANDROID -> builder.value("clientScreen", "WATCH")
                .value("androidSdkVersion", 36)
            YoutubeSabrClientProfile.ANDROID_VR -> builder.value("clientScreen", "WATCH")
                .value("deviceMake", "Oculus")
                .value("deviceModel", "Quest 3")
                .value("androidSdkVersion", 32)
            YoutubeSabrClientProfile.IOS -> builder.value("clientScreen", "WATCH")
                .value("deviceMake", "Apple")
                .value("deviceModel", "iPhone16,2")
            YoutubeSabrClientProfile.WEB_EMBEDDED -> builder.value("clientScreen", "EMBED")
            else -> {}
        }

        builder.end()
            .`object`("request")
            .array("internalExperimentFlags")
            .end()
            .value("useSsl", true)
            .end()
            .`object`("user")
            .value("lockedSafetyMode", false)
            .end()
            .end()
            .`object`("playbackContext")
            .`object`("contentPlaybackContext")
            .value("referer", "https://www.youtube.com/watch?v=$videoId")
            .value("vis", 0)
            .value("splay", false)
            .value("lactMilliseconds", "-1")
            .value(
                "signatureTimestamp",
                YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
            )
            .value("html5Preference", "HTML5_PREF_WANTS")
            .end()
            .end()
            .value(YoutubeParsingHelper.CPN, cpn)
            .value(YoutubeParsingHelper.VIDEO_ID, videoId)
            .value(YoutubeParsingHelper.CONTENT_CHECK_OK, true)
            .value(YoutubeParsingHelper.RACY_CHECK_OK, true)

        if (!playerPoToken.isNullOrEmpty()) {
            builder.`object`("serviceIntegrityDimensions")
                .value("poToken", playerPoToken)
                .end()
        }

        return builder.done().toString().toByteArray(Charsets.UTF_8)
    }

    private fun getInnertubeBaseUrl(profile: YoutubeSabrClientProfile): String =
        if (profile == YoutubeSabrClientProfile.ANDROID ||
            profile == YoutubeSabrClientProfile.ANDROID_VR ||
            profile == YoutubeSabrClientProfile.IOS
        ) {
            YoutubeParsingHelper.YOUTUBEI_V1_GAPIS_URL
        } else {
            YoutubeParsingHelper.YOUTUBEI_V1_URL
        }

    @Throws(IOException::class, ExtractionException::class)
    private fun buildPlayerHeaders(
        profile: YoutubeSabrClientProfile,
        visitorDataOverride: String?
    ): Map<String, List<String>> {
        val headers = HashMap<String, List<String>>()
        headers["Content-Type"] = listOf("application/json")
        if (!visitorDataOverride.isNullOrEmpty()) {
            headers["X-Goog-Visitor-Id"] = listOf(visitorDataOverride)
        }
        profile.getUserAgent()?.let { headers["User-Agent"] = listOf(it) }
        if (profile == YoutubeSabrClientProfile.ANDROID ||
            profile == YoutubeSabrClientProfile.ANDROID_VR ||
            profile == YoutubeSabrClientProfile.IOS
        ) {
            headers["X-Goog-Api-Format-Version"] = listOf("2")
        } else {
            headers["Origin"] = listOf("https://www.youtube.com")
            headers["Referer"] = listOf("https://www.youtube.com")
            headers["X-YouTube-Client-Name"] = listOf(profile.clientId)
            headers["X-YouTube-Client-Version"] = listOf(resolveClientVersion(profile))
            YoutubeParsingHelper.addLoggedInHeaders(headers)
            if (!headers.containsKey("Cookie")) {
                YoutubeParsingHelper.addCookieHeader(headers)
            }
        }
        return headers
    }

    private fun buildSabrHeaders(info: YoutubeSabrInfo): Map<String, List<String>> {
        val headers = HashMap<String, List<String>>()
        headers["Content-Type"] = listOf("application/x-protobuf")
        headers["Accept"] = listOf("application/vnd.yt-ump")
        headers["Accept-Encoding"] = listOf("identity")
        info.profile.getUserAgent()?.let { headers["User-Agent"] = listOf(it) }
        if (!isWebSabrProfile(info.profile) && !info.visitorData.isNullOrEmpty()) {
            headers["X-Goog-Visitor-Id"] = listOf(info.visitorData)
        }
        if (isWebSabrProfile(info.profile)) {
            headers.remove("Content-Type")
            headers.remove("Accept-Encoding")
            headers["Accept"] = listOf("*/*")
            headers["Accept-Language"] = listOf("en-US,en;q=0.9")
            headers["Origin"] = listOf("https://www.youtube.com")
            headers["Referer"] = listOf("https://www.youtube.com/")
        }
        return headers
    }

    private fun isWebSabrProfile(profile: YoutubeSabrClientProfile): Boolean =
        profile.isWebLike() || profile == YoutubeSabrClientProfile.WEB

    private fun withSabrSessionParameters(
        url: String,
        cpn: String,
        requestNumber: Int
    ): String {
        var result = appendQueryParameterIfMissing(url, "alr", "yes")
        result = appendQueryParameterIfMissing(result, "cpn", cpn)
        return setQueryParameter(result, "rn", (requestNumber + 1).toString())
    }

    private fun appendQueryParameterIfMissing(
        url: String,
        name: String,
        value: String
    ): String {
        if (url.contains("?$name=") || url.contains("&$name=")) {
            return url
        }
        return appendQueryParameter(url, name, value)
    }

    private fun appendQueryParameter(url: String, name: String, value: String): String {
        val separator = if (url.contains("?")) "&" else "?"
        return "$url$separator$name=$value"
    }

    private fun setQueryParameter(url: String, name: String, value: String): String {
        val fragmentIndex = url.indexOf('#')
        val baseUrl = if (fragmentIndex < 0) url else url.substring(0, fragmentIndex)
        val fragment = if (fragmentIndex < 0) "" else url.substring(fragmentIndex)
        val queryIndex = baseUrl.indexOf('?')
        val path = if (queryIndex < 0) baseUrl else baseUrl.substring(0, queryIndex)
        val query = if (queryIndex < 0) "" else baseUrl.substring(queryIndex + 1)

        val result = StringBuilder(path).append('?')
        var wroteParameter = false
        for (parameter in query.split("&")) {
            if (parameter.isEmpty()) {
                continue
            }
            val equalsIndex = parameter.indexOf('=')
            val parameterName = if (equalsIndex < 0) {
                parameter
            } else {
                parameter.substring(0, equalsIndex)
            }
            if (parameterName == name) {
                continue
            }
            if (wroteParameter) {
                result.append('&')
            }
            result.append(parameter)
            wroteParameter = true
        }
        if (wroteParameter) {
            result.append('&')
        }
        return result.append(name).append('=').append(value).append(fragment).toString()
    }

    @Throws(ParsingException::class)
    private fun resolveClientVersion(profile: YoutubeSabrClientProfile): String {
        if (profile == YoutubeSabrClientProfile.WEB ||
            profile == YoutubeSabrClientProfile.MWEB
        ) {
            return try {
                YoutubeParsingHelper.getClientVersion()
            } catch (e: Exception) {
                profile.clientVersion
            }
        }
        return profile.clientVersion
    }

    private fun extractVisitorData(response: JsonObject): String? =
        response.getObject("responseContext")?.getString("visitorData")

    private fun extractVideoPlaybackUstreamerConfig(response: JsonObject): String? =
        response.getObject("playerConfig")
            ?.getObject("mediaCommonConfig")
            ?.getObject("mediaUstreamerRequestConfig")
            ?.getString("videoPlaybackUstreamerConfig")
}
