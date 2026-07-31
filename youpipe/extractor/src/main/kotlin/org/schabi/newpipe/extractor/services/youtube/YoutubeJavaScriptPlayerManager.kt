package org.schabi.newpipe.extractor.services.youtube

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.utils.JsonUtils
import org.schabi.newpipe.extractor.utils.Parser
import org.schabi.newpipe.extractor.utils.getInt
import org.schabi.newpipe.extractor.utils.getString
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Manage the extraction and the usage of YouTube's player JavaScript needed data in the YouTube
 * service.
 *
 * YouTube restrict streaming their media in multiple ways by requiring their HTML5 clients to use
 * a signature timestamp, and on streaming URLs a signature deobfuscation function for some
 * contents and a throttling parameter deobfuscation one for all contents.
 *
 * This class provides access to methods which allows to get base JavaScript player's signature
 * timestamp and to deobfuscate streaming URLs' signature and/or throttling parameter of HTML5
 * clients using the PipePipe API.
 */
object YoutubeJavaScriptPlayerManager {

    private val THROTTLING_PARAM_PATTERN = Pattern.compile("[&?]n=([^&]+)")

    private const val LATEST_PLAYER_URL = "https://api.pipepipe.dev/decoder/latest-player"
    private const val USER_AGENT = "PipePipe/4.9.0"
    private const val PLAYER_METADATA_TTL_MILLIS = 24L * 60L * 60L * 1000L

    private var playerMetadata: PlayerMetadata? = null

    /**
     * Get the signature timestamp of the base JavaScript player file.
     *
     * A valid signature timestamp sent in the payload of player InnerTube requests is required to
     * get valid stream URLs on HTML5 clients for videos which have obfuscated signatures.
     *
     * The signature timestamp is loaded together with the player ID from the decoder API, and is
     * reused for up to 24 hours before being refreshed.
     *
     * @param videoId the video ID used to get the JavaScript base player file (an empty one can be
     *                passed, even it is not recommend in order to spoof better official YouTube
     *                clients)
     * @return the signature timestamp of the base JavaScript player file
     * @throws ParsingException if the extraction of the signature timestamp failed
     */
    @JvmStatic
    @Throws(ParsingException::class)
    fun getSignatureTimestamp(videoId: String): Int {
        val startedAtNanos = System.nanoTime()
        val signatureTimestamp = getPlayerMetadata(videoId).signatureTimestamp
        logPerformance(videoId, "ejs.signatureTimestamp", startedAtNanos)
        return signatureTimestamp
    }

    /**
     * Deobfuscate a signature of a streaming URL using the PipePipe API.
     *
     * Obfuscated signatures are only present on streaming URLs of some videos with HTML5 clients.
     *
     * @param videoId             the video ID used to get the JavaScript base player ID (an
     *                            empty one can be passed, even it is not recommend in order to
     *                            spoof better official YouTube clients)
     * @param obfuscatedSignature the obfuscated signature of a streaming URL
     * @return the deobfuscated signature
     * @throws ParsingException if the extraction of the player ID or the API call failed
     */
    @JvmStatic
    @Throws(ParsingException::class)
    fun deobfuscateSignature(videoId: String, obfuscatedSignature: String): String =
        YoutubeApiDecoder.decodeSignature(
            getPlayerMetadata(videoId).playerId, obfuscatedSignature
        )

    /**
     * Return a streaming URL with the throttling parameter of a given one deobfuscated, if it is
     * present, using the PipePipe API.
     *
     * The throttling parameter is present on all streaming URLs of HTML5 clients. If it is not
     * given or deobfuscated, speeds will be throttled to a very slow speed (around 50 KB/s) and
     * some streaming URLs could even lead to invalid HTTP responses such a 403 one.
     *
     * @param videoId      the video ID used to get the JavaScript base player ID (an empty one
     *                     can be passed, even it is not recommend in order to spoof better
     *                     official YouTube clients)
     * @param streamingUrl a streaming URL
     * @return the original streaming URL if it has no throttling parameter or a URL with a
     * deobfuscated throttling parameter
     * @throws ParsingException if the extraction of the player ID or the API call failed
     */
    @JvmStatic
    @Throws(ParsingException::class)
    fun getUrlWithThrottlingParameterDeobfuscated(
        videoId: String,
        streamingUrl: String
    ): String {
        // If the throttling parameter is not present, return the original streaming URL
        val obfuscatedThrottlingParameter =
            getThrottlingParameterFromStreamingUrl(streamingUrl) ?: return streamingUrl

        val metadata = getPlayerMetadata(videoId)

        val deobfuscatedThrottlingParameter = YoutubeApiDecoder.decodeThrottlingParameter(
            metadata.playerId, obfuscatedThrottlingParameter
        )

        return streamingUrl.replace(
            obfuscatedThrottlingParameter, deobfuscatedThrottlingParameter
        )
    }

    /**
     * Clear the cached player metadata.
     *
     * The next access will fetch a fresh player ID and signature timestamp from the API.
     */
    @JvmStatic
    fun clearAllCaches() {
        playerMetadata = null
        YoutubeApiDecoder.clearCache()
    }

    @JvmStatic
    fun clearThrottlingParametersCache() {
        YoutubeApiDecoder.clearCache()
    }

    @JvmStatic
    fun getThrottlingParametersCacheSize(): Int = YoutubeApiDecoder.getCacheSize()

    @JvmStatic
    fun getThrottlingParameterFromStreamingUrl(streamingUrl: String): String? =
        try {
            Parser.matchGroup1(THROTTLING_PARAM_PATTERN, streamingUrl)
        } catch (e: Parser.RegexException) {
            null
        }

    /**
     * Batch deobfuscate multiple signatures and throttling parameters in a single API call.
     *
     * This method is more efficient than calling [deobfuscateSignature] and
     * [getUrlWithThrottlingParameterDeobfuscated] individually for each stream, as it combines all
     * parameters into a single API request.
     *
     * @param videoId          the video ID used to get the JavaScript base player ID
     * @param signatures       list of obfuscated signatures to decode (can be null or empty)
     * @param throttlingParams list of obfuscated throttling parameters to decode (can be null or
     *                         empty)
     * @return a BatchDecodeResult containing decoded signatures and throttling parameters
     * @throws ParsingException if the extraction of the player ID or the API call failed
     */
    @JvmStatic
    @Throws(ParsingException::class)
    fun deobfuscateBatch(
        videoId: String,
        signatures: List<String>?,
        throttlingParams: List<String>?
    ): YoutubeApiDecoder.BatchDecodeResult {
        val playerId = getPlayerMetadata(videoId).playerId
        val startedAtNanos = System.nanoTime()
        val result = YoutubeApiDecoder.decodeBatch(playerId, signatures, throttlingParams)
        logPerformanceIfSlow(videoId, "ejs.batch.decode", startedAtNanos)
        return result
    }

    /**
     * Load player metadata from memory or refresh it from the decoder API.
     *
     * @param videoId unused, kept to avoid changing public call sites
     * @throws ParsingException if loading the player metadata failed
     */
    @Throws(ParsingException::class)
    private fun getPlayerMetadata(videoId: String): PlayerMetadata {
        val currentMetadata = playerMetadata
        if (currentMetadata != null && !currentMetadata.isExpired()) {
            return currentMetadata
        }

        val startedAtNanos = System.nanoTime()
        val decoder = YoutubeApiDecoder.getLocalDecoder()
        val metadata = if (decoder != null) {
            val data = decoder.getPlayerData(videoId)
            PlayerMetadata(
                data.getPlayerId(),
                data.getSignatureTimestamp(),
                System.currentTimeMillis() + PLAYER_METADATA_TTL_MILLIS
            ).also { logPerformance(videoId, "ejs.playerMetadata.local", startedAtNanos) }
        } else {
            fetchLatestPlayerMetadata()
                .also { logPerformance(videoId, "ejs.playerMetadata.remoteFallback", startedAtNanos) }
        }
        playerMetadata = metadata
        return metadata
    }

    private fun logPerformance(videoId: String, stage: String, startedAtNanos: Long) {
        println(
            "YT_PERF videoId=$videoId stage=$stage durationMs=" +
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
        )
    }

    private fun logPerformanceIfSlow(videoId: String, stage: String, startedAtNanos: Long) {
        val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
        if (durationMs >= 5) {
            println("YT_PERF videoId=$videoId stage=$stage durationMs=$durationMs")
        }
    }

    @Throws(ParsingException::class)
    private fun fetchLatestPlayerMetadata(): PlayerMetadata {
        val headers = mapOf("User-Agent" to listOf(USER_AGENT))

        try {
            val response = NewPipe.getDownloader().get(
                LATEST_PLAYER_URL, headers, Localization.DEFAULT
            )
            val responseJson = JsonUtils.toJsonObject(response.responseBody())

            val playerId = responseJson.getString("player", "")
            if (playerId.isEmpty()) {
                throw ParsingException("latest-player response missing player")
            }

            val signatureTimestamp = responseJson.getInt("signatureTimestamp")
                ?: throw ParsingException("latest-player response missing signatureTimestamp")

            return PlayerMetadata(
                playerId,
                signatureTimestamp,
                System.currentTimeMillis() + PLAYER_METADATA_TTL_MILLIS
            )
        } catch (e: IOException) {
            throw ParsingException("Failed to fetch latest player metadata", e)
        } catch (e: ReCaptchaException) {
            throw ParsingException("Failed to fetch latest player metadata", e)
        }
    }

    private class PlayerMetadata(
        val playerId: String,
        val signatureTimestamp: Int,
        private val expiresAt: Long
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() >= expiresAt
    }
}
